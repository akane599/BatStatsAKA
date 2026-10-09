package com.akane.voltwise.battery.insights.actions

import com.akane.voltwise.battery.actions.*
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.db.InsightActionStatus.*
import com.akane.voltwise.battery.insights.UnusedInsightDao
import com.akane.voltwise.battery.insights.model.*
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InsightActionRepositoryTest {
    private val pkg = "com.example.drainer"
    private val uid = 10123
    private fun finding(subject: Subject = Subject.App(uid, pkg)) = Finding(
        "finding", FindingType.BACKGROUND_RUNAWAY, Severity.HIGH, Confidence.HIGH, 1.0,
        subject, null, emptyList(), emptyList(), emptyList(),
    )
    private fun rec(type: ActionType) = Recommendation(type, true, true)
    private fun ok(output: String = "") = ShellRunner.Outcome.Success(output, ShellRunner.Mode.ROOT)
    private fun denied() = ShellRunner.Outcome.NoAccess(ShellRunner.Mode.NONE, "sensitive diagnostic")
    private fun failure() = ShellRunner.Outcome.Failure(ShellRunner.Mode.ROOT, "sensitive diagnostic")

    private inner class Inspector : TargetInspector {
        var inspectionFails = false
        var installed: Int? = uid
        var packages = listOf(pkg)
        var roles = emptySet<String>()
        override var sdkInt = 35
        override fun installedUid(pkg: String, userId: Int): Int? { assertEquals(0, userId); if (inspectionFails) throw SecurityException(); return installed }
        override fun packagesForUid(uid: Int) = packages
        override fun roleHolders() = roles
    }
    private class Dao : UnusedInsightDao() {
        val rows = MutableStateFlow<List<InsightActionEntity>>(emptyList())
        override fun actions() = rows
        override suspend fun actionsOnce() = rows.value
        override suspend fun actionsWithStatus(statuses: List<InsightActionStatus>) = rows.value.filter { it.status in statuses }
        override suspend fun insertAction(entity: InsightActionEntity): Long {
            val id = (rows.value.maxOfOrNull { it.id } ?: 0) + 1
            rows.value += entity.copy(id = id)
            return id
        }
        override suspend fun updateAction(entity: InsightActionEntity) {
            check(rows.value.any { it.id == entity.id })
            rows.value = rows.value.map { if (it.id == entity.id) entity else it }
        }
    }
    private inner class Fixture {
        val dao = Dao()
        val inspector = Inspector()
        val commands = mutableListOf<PrivilegedCommand>()
        val replies = ArrayDeque<ShellRunner.Outcome>()
        var intercept: suspend (PrivilegedCommand) -> Unit = {}
        var now = 100L
        var alerts = 0
        val repo = InsightActionRepository(dao, ActionExecutor {
            commands += it
            intercept(it)
            replies.removeFirst()
        }, inspector, { now++ }, { alerts++ })
        fun reply(vararg output: String) { replies.addAll(output.map(::ok)) }
        fun row() = dao.rows.value.single()
        suspend fun apply(type: ActionType = ActionType.RESTRICT_BACKGROUND) = repo.apply(finding(), rec(type))
    }

    @Test fun applyStoresLeadMetricInPreparedAndAppliedJournal() = runTest {
        for (metric in listOf(Metric.JOBS_PER_H, null)) {
            val f = Fixture()
            val appliedFinding = finding().copy(
                type = FindingType.JOB_STORM,
                evidence = metric?.let { listOf(Evidence(it, 60.0, 1.0, it.unit, 5)) } ?: emptyList(),
            )
            f.reply("No operations.", "", "RUN_ANY_IN_BACKGROUND: ignore")
            f.intercept = {
                if (it is PrivilegedCommand.SetBackgroundOp) {
                    assertEquals(PREPARED, f.row().status)
                    assertEquals(metric?.name, f.row().metric)
                }
            }
            assertEquals(ActionResult.Applied(1), f.repo.apply(appliedFinding, rec(ActionType.RESTRICT_BACKGROUND)))
            assertEquals(APPLIED, f.row().status)
            assertEquals(metric?.name, f.row().metric)
        }
    }

    @Test fun backgroundRoundTripRestoresEffectiveAllowAndPublishesJournal() = runTest {
        val f = Fixture()
        f.reply("No operations.", "", "RUN_ANY_IN_BACKGROUND: ignore")
        f.intercept = { if (it is PrivilegedCommand.SetBackgroundOp) assertEquals(PREPARED, f.row().status) }
        assertEquals(ActionResult.Applied(1), f.apply())
        assertEquals(APPLIED, f.repo.actions.first().single().status)
        assertEquals("RUN_ANY_IN_BACKGROUND:ALLOW", f.row().priorState)
        assertEquals(1, f.row().priorStateVersion)
        f.intercept = {}
        f.reply("RUN_ANY_IN_BACKGROUND: ignore", "", "No operations.")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
        assertEquals(PrivilegedCommand.SetBackgroundOp(pkg, BackgroundOp.RUN_ANY_IN_BACKGROUND, AppOpMode.ALLOW), f.commands[4])
        assertEquals(REVERTED, f.row().status)
        assertNotNull(f.row().revertedAt)
    }

    @Test fun bucketsRoundTripAndRestrictedFallback() = runTest {
        for ((sdk, type, target) in listOf(
            Triple(35, ActionType.STANDBY_BUCKET_RESTRICTED, StandbyBucket.RESTRICTED),
            Triple(29, ActionType.STANDBY_BUCKET_RESTRICTED, StandbyBucket.RARE),
            Triple(28, ActionType.STANDBY_BUCKET_RARE, StandbyBucket.RARE),
        )) {
            val f = Fixture()
            f.inspector.sdkInt = sdk
            f.reply("20", "", target.code.toString())
            val result = f.apply(type)
            if (sdk == 29) assertEquals(ActionResult.AppliedWithFallback(1, FallbackCode.RESTRICTED_TO_RARE), result)
            else assertEquals(ActionResult.Applied(1), result)
            assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, target), f.commands[1])
            f.reply(target.token, "", "working_set")
            assertEquals(ActionResult.Reverted, f.repo.undo(1))
            assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.WORKING_SET), f.commands[4])
        }
    }

    @Test fun dozeUndoRestoresOnlyUserMembershipAndRequiresListConfirmation() = runTest {
        val f = Fixture()
        val system = "system,android,1000"
        f.reply("$system\nuser,$pkg,$uid", "Unknown package: $pkg", system)
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.REMOVE_DOZE_WHITELIST))
        assertEquals("PRESENT", f.row().priorState)
        f.reply(system, "Added: $pkg", "$system\nuser,$pkg,$uid")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
        assertEquals(PrivilegedCommand.AddDozeWhitelist(pkg), f.commands[4])
    }

    @Test fun reapplyAfterExternalDozeChangeClosesOldRowBeforeWritingAndPreventsOldUndo() = runTest {
        val f = Fixture()
        val system = "system,android,1000"
        val present = "$system\nuser,$pkg,$uid"
        f.reply(present, "Removed: $pkg", system)
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.REMOVE_DOZE_WHITELIST))
        val old = f.row()

        // Settings re-adds the app; the next apply observes that live membership.
        f.reply(present, "Removed: $pkg", system)
        f.intercept = {
            if (it is PrivilegedCommand.RemoveDozeWhitelist) {
                val closed = f.dao.rows.value.first { row -> row.id == old.id }
                assertEquals(REVERTED, closed.status)
                assertEquals("CHANGED_EXTERNALLY", closed.message)
                assertNotNull(closed.revertedAt)
                assertEquals(old.appliedAt, closed.appliedAt)
                assertEquals(PREPARED, f.dao.rows.value.last().status)
            }
        }
        assertEquals(ActionResult.Applied(2), f.apply(ActionType.REMOVE_DOZE_WHITELIST))
        assertEquals(APPLIED, f.dao.rows.value.last().status)
        val commandsBeforeUndo = f.commands.size
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(old.id))
        assertEquals(commandsBeforeUndo, f.commands.size)
        assertEquals(APPLIED, f.dao.rows.value.last().status)

        f.intercept = {}
        f.reply(system, "Added: $pkg", present)
        assertEquals(ActionResult.Reverted, f.repo.undo(2))
        assertEquals(PrivilegedCommand.AddDozeWhitelist(pkg), f.commands[7])
    }

    @Test fun applyClosesOnlyAppliedRowsWithMatchingTypeAndPackageAndDifferentTarget() = runTest {
        val f = Fixture()
        f.reply("active", "", "rare")
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RARE))
        val stale = f.row()
        val retained = listOf(
            stale.copy(id = 2, packageName = "com.example.other"),
            stale.copy(id = 3, type = ActionType.STANDBY_BUCKET_RESTRICTED.name),
            stale.copy(id = 4, status = UNKNOWN),
            stale.copy(id = 5, targetState = "WORKING_SET"),
        )
        f.dao.rows.value += retained
        f.reply("working_set", "", "rare")
        assertEquals(ActionResult.Applied(6), f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertEquals(REVERTED, f.dao.rows.value.first().status)
        assertEquals("CHANGED_EXTERNALLY", f.dao.rows.value.first().message)
        assertEquals(retained, f.dao.rows.value.filter { it.id in 2L..5L })
    }

    @Test fun unknownPriorDoesNotCloseAppliedRows() = runTest {
        val f = Fixture()
        f.reply("active", "", "rare")
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RARE))
        val old = f.row()
        f.reply("garbage")
        assertEquals(ActionResult.Failed(FailureCode.READ_FAILED), f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertEquals(old, f.row())
    }

    @Test fun refusesUnrestorablePriorStatesWithoutMutationOrJournal() = runTest {
        for (mode in listOf("default", "deny", "foreground")) {
            val f = Fixture()
            f.reply("RUN_ANY_IN_BACKGROUND: $mode", "", "RUN_ANY_IN_BACKGROUND: ignore")
            assertEquals(ActionResult.Refused(RefusalCode.UNRESTORABLE_PRIOR), f.apply())
            assertEquals(1, f.commands.size)
            assertTrue(f.dao.rows.value.isEmpty())
        }
        for (bucket in listOf("exempted", "never")) {
            val f = Fixture()
            f.reply(bucket, "", "rare")
            assertEquals(ActionResult.Refused(RefusalCode.UNRESTORABLE_PRIOR), f.apply(ActionType.STANDBY_BUCKET_RARE))
            assertEquals(1, f.commands.size)
            assertTrue(f.dao.rows.value.isEmpty())
        }
    }

    @Test fun refusesUnsafeIdentitiesBeforeAnyCommand() = runTest {
        val cases: List<Pair<RefusalCode, (Inspector) -> Unit>> = listOf(
            RefusalCode.NOT_INSTALLED to { it.installed = null },
            RefusalCode.UID_MISMATCH to { it.installed = uid + 1 },
            RefusalCode.SHARED_UID to { it.packages = listOf(pkg, "com.example.other") },
            RefusalCode.SHARED_UID to { it.packages = emptyList() },
            RefusalCode.ROLE_HOLDER to { it.roles = setOf(pkg) },
        )
        for ((code, setup) in cases) {
            val f = Fixture(); setup(f.inspector)
            assertEquals(ActionResult.Refused(code), f.apply())
            assertTrue(f.commands.isEmpty()); assertTrue(f.dao.rows.value.isEmpty())
        }
        for (app in listOf(Subject.App(9999, pkg), Subject.App(uid, "com.android.systemui"), Subject.App(110123, pkg))) {
            val f = Fixture()
            assertEquals(ActionResult.Refused(RefusalCode.PROTECTED), f.repo.apply(finding(app), rec(ActionType.FORCE_STOP)))
            assertTrue(f.commands.isEmpty())
        }
        val f = Fixture()
        assertEquals(ActionResult.Refused(RefusalCode.INVALID_SUBJECT), f.repo.apply(finding(Subject.Device), rec(ActionType.FORCE_STOP)))
        assertEquals(ActionResult.Refused(RefusalCode.INVALID_PACKAGE), f.repo.apply(finding(Subject.App(uid, "bad;pkg")), rec(ActionType.FORCE_STOP)))
    }

    @Test fun sdkGatingAndLegacyOp() = runTest {
        val f = Fixture(); f.inspector.sdkInt = 26
        assertEquals(ActionResult.Refused(RefusalCode.UNSUPPORTED_SDK), f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertTrue(f.commands.isEmpty())
        f.reply("No operations.", "", "RUN_IN_BACKGROUND: ignore")
        assertEquals(ActionResult.Applied(1), f.apply())
        f.inspector.sdkInt = 35
        f.reply("RUN_IN_BACKGROUND: ignore", "", "RUN_IN_BACKGROUND: allow")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
        assertEquals(PrivilegedCommand.SetBackgroundOp(pkg, BackgroundOp.RUN_IN_BACKGROUND, AppOpMode.ALLOW), f.commands[4])
    }

    @Test fun preflightNoAccessWritesNoRow() = runTest {
        for (type in listOf(ActionType.RESTRICT_BACKGROUND, ActionType.FORCE_STOP)) {
            val f = Fixture(); f.replies += denied()
            assertEquals(ActionResult.Refused(RefusalCode.NOT_PRIVILEGED), f.apply(type))
            assertTrue(f.dao.rows.value.isEmpty())
        }
    }

    @Test fun executeNoAccessRetainsUnknownUndoAuthority() = runTest {
        val f = Fixture(); f.replies.addAll(listOf(ok("No operations."), denied()))
        assertEquals(ActionResult.Unknown, f.apply())
        assertEquals(UNKNOWN, f.row().status); assertEquals(2, f.commands.size)
        f.reply("RUN_ANY_IN_BACKGROUND: ignore")
        f.repo.reconcile()
        assertEquals(APPLIED, f.row().status)
    }

    @Test fun confirmationNoAccessRetainsUnknownUndoAuthority() = runTest {
        val f = Fixture(); f.replies.addAll(listOf(ok("No operations."), ok(), denied()))
        assertEquals(ActionResult.Unknown, f.apply()); assertEquals(UNKNOWN, f.row().status)
        f.reply("RUN_ANY_IN_BACKGROUND: ignore", "", "No operations.")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
    }

    @Test fun crashAfterExecuteLeavesPreparedAndReconcileNeverReplays() = runTest {
        for ((output, status) in listOf("rare" to APPLIED, "active" to FAILED, "frequent" to UNKNOWN, "garbage" to UNKNOWN)) {
            val f = Fixture(); f.reply("active", "")
            var reads = 0
            f.intercept = { if (it is PrivilegedCommand.GetStandbyBucket && ++reads == 2) throw IllegalStateException("simulated crash") }
            try { f.apply(ActionType.STANDBY_BUCKET_RARE); fail("crash required") } catch (_: IllegalStateException) { }
            assertEquals(PREPARED, f.row().status)
            f.intercept = {}; f.reply(output)
            val writes = f.commands.count { it is PrivilegedCommand.SetStandbyBucket }
            f.repo.reconcile()
            assertEquals(status, f.row().status)
            assertEquals(writes, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
        }
    }

    @Test fun unknownAndFailedReadbacksNeverImplyApplied() = runTest {
        for (confirmation in listOf(ok("garbage"), failure())) {
            val f = Fixture(); f.replies.addAll(listOf(ok("No operations."), ok(), confirmation))
            assertEquals(ActionResult.Unknown, f.apply()); assertEquals(UNKNOWN, f.row().status)
        }
        val f = Fixture(); f.reply("No operations.", "", "No operations.")
        assertEquals(ActionResult.Failed(FailureCode.STATE_MISMATCH), f.apply())
        assertEquals(FAILED, f.row().status)
        val failed = Fixture(); failed.replies += failure()
        assertEquals(ActionResult.Failed(FailureCode.READ_FAILED), failed.apply()); assertTrue(failed.dao.rows.value.isEmpty())
    }

    @Test fun externalChangeSettlesRevertedWithoutMutation() = runTest {
        for (current in listOf(StandbyBucket.ACTIVE, StandbyBucket.FREQUENT)) {
            val f = Fixture(); f.reply("active", "", "rare")
            assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RARE))
            val appliedAt = f.row().appliedAt
            f.reply(current.token)
            assertEquals(ActionResult.ChangedExternally(current.name), f.repo.undo(1))
            assertEquals(REVERTED, f.row().status)
            assertEquals("CHANGED_EXTERNALLY", f.row().message)
            assertEquals(appliedAt, f.row().appliedAt)
            assertNotNull(f.row().revertedAt)
            assertEquals(4, f.commands.size)
            assertEquals(1, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
            assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(1))
            assertEquals(4, f.commands.size)
        }
    }

    @Test fun undoRevalidatesIdentityAndRejectsInvalidJournal() = runTest {
        val f = Fixture(); f.reply("active", "", "rare"); f.apply(ActionType.STANDBY_BUCKET_RARE)
        f.dao.updateAction(f.row().copy(priorStateVersion = 2))
        assertEquals(ActionResult.Failed(FailureCode.INVALID_JOURNAL), f.repo.undo(1))
        f.repo.reconcile(); assertEquals(3, f.commands.size)
    }

    @Test fun forceStopIsOneShotAndFailuresUseFixedCodes() = runTest {
        val f = Fixture(); f.reply()
        f.replies += ok()
        assertEquals(ActionResult.OneShot(1), f.apply(ActionType.FORCE_STOP))
        assertEquals(ONE_SHOT, f.row().status); assertEquals(1, f.commands.size)
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(1))
        val failed = Fixture(); failed.replies += failure()
        assertEquals(ActionResult.Failed(FailureCode.EXECUTION_FAILED), failed.apply(ActionType.FORCE_STOP))
        assertEquals("EXECUTION_FAILED", failed.row().message)
    }

    @Test fun manualSettingsAreNotJournaledAndAlertNeedsNoPrivilege() = runTest {
        val f = Fixture()
        assertEquals(ActionResult.OpenSettings(IntentSpec("android.settings.APPLICATION_DETAILS_SETTINGS", pkg)), f.apply(ActionType.OPEN_APP_SETTINGS))
        assertEquals(ActionResult.OpenSettings(IntentSpec("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")), f.apply(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS))
        assertTrue(f.dao.rows.value.isEmpty())
        assertEquals(ActionResult.OneShot(1), f.repo.apply(finding(Subject.Device), rec(ActionType.ENABLE_HIGH_BATTERY_ALERT)))
        assertEquals(1, f.alerts); assertEquals(ONE_SHOT, f.row().status); assertTrue(f.commands.isEmpty())
    }

    @Test fun interruptedUndoRetainsUnknownAndNoAccessDoesNotClaimRefusalAfterWrite() = runTest {
        for (atWrite in listOf(true, false)) {
            val f = Fixture(); f.reply("active", "", "rare"); f.apply(ActionType.STANDBY_BUCKET_RARE)
            f.replies += ok("rare")
            if (!atWrite) f.replies += ok()
            f.replies += denied()
            assertEquals(ActionResult.Unknown, f.repo.undo(1))
            assertEquals(UNKNOWN, f.row().status)
        }
    }

    private suspend fun Fixture.interruptUndoAfterRestore(crash: Boolean) {
        reply("active", "", "rare")
        assertEquals(ActionResult.Applied(1), apply(ActionType.STANDBY_BUCKET_RARE))
        reply("rare", "")
        if (crash) {
            intercept = {
                if (it is PrivilegedCommand.GetStandbyBucket && commands.count { command -> command is PrivilegedCommand.GetStandbyBucket } == 4) {
                    throw IllegalStateException("simulated crash after restore")
                }
            }
            try { repo.undo(1); fail("crash required") } catch (_: IllegalStateException) { }
            intercept = {}
        } else {
            replies += denied()
            assertEquals(ActionResult.Unknown, repo.undo(1))
        }
        assertEquals(UNKNOWN, row().status)
        assertNotNull(row().appliedAt)
        assertNull(row().revertedAt)
        assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.ACTIVE), commands[4])
    }

    @Test fun interruptedUndoAtPriorSettlesRevertedOnRetryWithoutMutation() = runTest {
        for (crash in listOf(false, true)) {
            val f = Fixture(); f.interruptUndoAfterRestore(crash)
            val appliedAt = f.row().appliedAt
            f.reply("active")
            assertEquals(ActionResult.Reverted, f.repo.undo(1))
            assertEquals(REVERTED, f.row().status)
            assertEquals(appliedAt, f.row().appliedAt)
            assertNotNull(f.row().revertedAt)
            assertNull(f.row().message)
            assertEquals(7, f.commands.size)
            assertEquals(2, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
        }
    }

    @Test fun interruptedUndoAtPriorReconcilesRevertedWithoutMutation() = runTest {
        for (crash in listOf(false, true)) {
            val f = Fixture(); f.interruptUndoAfterRestore(crash)
            val appliedAt = f.row().appliedAt
            f.reply("active")
            f.repo.reconcile()
            assertEquals(REVERTED, f.row().status)
            assertEquals(appliedAt, f.row().appliedAt)
            assertNotNull(f.row().revertedAt)
            assertNull(f.row().message)
            assertEquals(7, f.commands.size)
            assertEquals(2, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
            f.repo.reconcile()
            assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(1))
            assertEquals(7, f.commands.size)
        }
    }

    @Test fun applyPhaseUnknownAtPriorReconcilesFailedWithoutMutation() = runTest {
        val f = Fixture(); f.reply("active", "", "garbage")
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertNull(f.row().appliedAt)
        f.reply("active")
        f.repo.reconcile()
        assertEquals(FAILED, f.row().status)
        assertNull(f.row().appliedAt)
        assertNull(f.row().revertedAt)
        assertEquals(4, f.commands.size)
        assertEquals(1, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
    }

    @Test fun undoReadbackMustProveRestoration() = runTest {
        for ((output, result, status) in listOf(
            Triple("garbage", ActionResult.Unknown, UNKNOWN),
            Triple("frequent", ActionResult.Unknown, UNKNOWN),
        )) {
            val f = Fixture(); f.reply("active", "", "rare"); f.apply(ActionType.STANDBY_BUCKET_RARE)
            f.reply("rare", "", output)
            assertEquals(result, f.repo.undo(1)); assertEquals(status, f.row().status)
        }
    }

    @Test fun reconcileWillNotReadFutureJournal() = runTest {
        val f = Fixture(); f.reply("active", "", "garbage"); f.apply(ActionType.STANDBY_BUCKET_RARE)
        f.dao.updateAction(f.row().copy(type = "FUTURE_ACTION"))
        f.repo.reconcile()
        assertEquals(UNKNOWN, f.row().status); assertEquals(3, f.commands.size)
    }


    @Test fun undoTargetReadbackKeepsAuthorityAndSecondUndoSucceeds() = runTest {
        val f = Fixture(); f.reply("active", "", "rare")
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RARE))
        val appliedAt = f.row().appliedAt
        f.replies.addAll(listOf(ok("rare"), failure(), ok("rare")))
        assertEquals(ActionResult.Failed(FailureCode.STATE_MISMATCH), f.repo.undo(1))
        assertEquals(APPLIED, f.row().status)
        assertEquals("STATE_MISMATCH", f.row().message)
        assertEquals("ACTIVE", f.row().priorState)
        assertEquals(appliedAt, f.row().appliedAt)
        f.reply("rare", "", "active")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
        assertEquals(REVERTED, f.row().status)
        assertNull(f.row().message)
        assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.ACTIVE), f.commands[7])
    }

    @Test fun applyThirdStateRetainsUnknownUndoAuthority() = runTest {
        val f = Fixture(); f.reply("active", "", "frequent")
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertEquals(UNKNOWN, f.row().status)
        assertEquals("ACTIVE", f.row().priorState)
        f.reply("rare", "", "active")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
    }

    @Test fun restoreSafeRefusalsSettleReinstallAndAllowCriticalApps() = runTest {
        for (installed in listOf(uid + 1, null)) {
            val f = Fixture(); f.reply("active", "", "rare")
            f.apply(ActionType.STANDBY_BUCKET_RARE)
            f.inspector.installed = installed
            assertEquals(ActionResult.Reverted, f.repo.undo(1))
            assertEquals(REVERTED, f.row().status)
            assertNotNull(f.row().revertedAt)
            assertEquals(3, f.commands.size)
            for (status in listOf(PREPARED, UNKNOWN)) {
                f.dao.updateAction(f.row().copy(status = status, revertedAt = null))
                f.repo.reconcile()
                assertEquals(REVERTED, f.row().status)
                assertNotNull(f.row().revertedAt)
                assertEquals(3, f.commands.size)
            }
        }
        for (shared in listOf(false, true)) {
            val f = Fixture(); f.reply("active", "", "rare")
            f.apply(ActionType.STANDBY_BUCKET_RARE)
            if (shared) f.inspector.packages = listOf(pkg, "com.example.other")
            else f.inspector.roles = setOf(pkg)
            f.dao.updateAction(f.row().copy(status = UNKNOWN))
            f.reply("rare")
            f.repo.reconcile()
            assertEquals(APPLIED, f.row().status)
            f.reply("rare", "", "active")
            assertEquals(ActionResult.Reverted, f.repo.undo(1))
            assertEquals(REVERTED, f.row().status)
            assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.ACTIVE), f.commands[5])
        }
    }

    @Test fun undoKeepsHardSafetyRefusals() = runTest {
        for (code in listOf(RefusalCode.PROTECTED, RefusalCode.INVALID_PACKAGE, RefusalCode.INSPECTION_FAILED)) {
            val f = Fixture(); f.reply("active", "", "rare")
            f.apply(ActionType.STANDBY_BUCKET_RARE)
            when (code) {
                RefusalCode.PROTECTED -> f.dao.updateAction(f.row().copy(packageName = "com.android.systemui"))
                RefusalCode.INVALID_PACKAGE -> f.dao.updateAction(f.row().copy(packageName = "bad;pkg"))
                else -> f.inspector.inspectionFails = true
            }
            assertEquals(ActionResult.Refused(code), f.repo.undo(1))
            assertEquals(APPLIED, f.row().status)
            assertEquals(3, f.commands.size)
        }
        val f = Fixture(); f.inspector.inspectionFails = true
        assertEquals(ActionResult.Refused(RefusalCode.INSPECTION_FAILED), f.apply())
        assertTrue(f.commands.isEmpty())
        assertTrue(f.dao.rows.value.isEmpty())
    }

    @Test fun unrecognizedInitialReadFailsWithoutJournalOrMutation() = runTest {
        val f = Fixture(); f.reply("garbage")
        assertEquals(ActionResult.Failed(FailureCode.READ_FAILED), f.apply())
        assertTrue(f.dao.rows.value.isEmpty())
        assertEquals(listOf(PrivilegedCommand.GetBackgroundOp(pkg, BackgroundOp.RUN_ANY_IN_BACKGROUND)), f.commands)
    }

    @Test fun noOpAndLooseningApplyAreRefusedWithoutJournalOrMutation() = runTest {
        for ((type, output) in listOf(
            ActionType.RESTRICT_BACKGROUND to "RUN_ANY_IN_BACKGROUND: ignore",
            ActionType.STANDBY_BUCKET_RARE to "restricted",
            ActionType.STANDBY_BUCKET_RARE to "rare",
            ActionType.STANDBY_BUCKET_RESTRICTED to "restricted",
            ActionType.REMOVE_DOZE_WHITELIST to "system,$pkg,$uid",
        )) {
            val f = Fixture(); f.reply(output, "", output)
            val result = f.apply(type)
            assertTrue("Expected refusal for $type", result is ActionResult.Refused)
            assertEquals("ALREADY_AT_TARGET", (result as ActionResult.Refused).reason.name)
            assertTrue(f.dao.rows.value.isEmpty())
            assertEquals(1, f.commands.size)
        }
    }

    @Test fun unknownUndoAtPriorSettlesFailedWithoutMutation() = runTest {
        val f = Fixture(); f.reply("active", "", "garbage")
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
        f.reply("active")
        assertEquals(ActionResult.Failed(FailureCode.STATE_MISMATCH), f.repo.undo(1))
        assertEquals(FAILED, f.row().status)
        assertEquals("STATE_MISMATCH", f.row().message)
        assertEquals(4, f.commands.size)
        assertEquals(1, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
    }

    @Test fun concurrentApplyAndUndoAreSerializedAcrossPreparationAndReadback() = runTest {
        val f = Fixture(); f.reply("active", "", "rare", "rare", "", "active")
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        f.intercept = { if (it is PrivilegedCommand.SetStandbyBucket && it.bucket == StandbyBucket.RARE) { entered.complete(Unit); resume.await() } }
        val apply = async { f.apply(ActionType.STANDBY_BUCKET_RARE) }
        entered.await()
        val undo = async { f.repo.undo(1) }; runCurrent()
        assertFalse(undo.isCompleted); assertEquals(2, f.commands.size); assertEquals(PREPARED, f.row().status)
        resume.complete(Unit)
        assertEquals(ActionResult.Applied(1), apply.await())
        assertEquals(ActionResult.Reverted, undo.await())
        assertEquals(REVERTED, f.row().status)
    }
}
