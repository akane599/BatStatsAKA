#!/usr/bin/env python3
"""Check migrations against Room's exported schema using host SQLite (not device validation)."""
import json
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/app/batstats/battery/data/db/BatteryDatabase.kt').read_text()
fixture = (root / 'app/src/androidTest/java/app/batstats/battery/data/DatabaseMigrationTest.kt').read_text()
schema = json.loads((root / 'app/schemas/app.batstats.battery.data.db.BatteryDatabase/4.json').read_text())['database']

for version in (1, 2, 3):
    with sqlite3.connect(':memory:') as db:
        for sql in re.findall(r'legacy.execSQL\("([^"\n]+)"\)', fixture):
            if version == 1 and 'app_energy_stats' in sql:
                continue
            db.execute(sql)
        blocks = [source.split('val MIGRATION_3_4')[1].split('fun get(')[0]]
        if version == 1:
            blocks.insert(0, source.split('val MIGRATION_1_2')[1].split('val MIGRATION_2_3')[0])
        for block in blocks:
            statements = re.findall(r'db.execSQL\("([^"\n]+)"\)', block)
            assert len(statements) == block.count('db.execSQL'), 'Unsupported SQL expression in test reader'
            for sql in statements:
                db.execute(sql)
        for entity in schema['entities']:
            rows = db.execute('PRAGMA table_info(' + entity['tableName'] + ')').fetchall()
            actual = {r[1]: (r[2], bool(r[3]), r[4]) for r in rows}
            expected = {f['columnName']: (f['affinity'], f.get('notNull', False), f.get('defaultValue')) for f in entity['fields']}
            assert actual == expected, (version, entity['tableName'], actual, expected)
            primary = [r[1] for r in sorted(rows, key=lambda r: r[5]) if r[5] > 0]
            assert primary == entity['primaryKey']['columnNames'], (version, primary)
            indices = {r[1]: bool(r[2]) for r in db.execute('PRAGMA index_list(' + entity['tableName'] + ')') if not r[1].startswith('sqlite_autoindex')}
            assert indices == {i['name']: i.get('unique', False) for i in entity.get('indices', [])}, (version, indices)
        assert db.execute('SELECT COUNT(*) FROM battery_samples').fetchone()[0] == 2
        assert db.execute('SELECT currentNowUa FROM battery_samples WHERE id=1').fetchone()[0] == 0
        assert db.execute('SELECT currentNowUa FROM battery_samples WHERE id=2').fetchone()[0] is None
        assert db.execute("SELECT endTime FROM charge_sessions WHERE sessionId='interrupted'").fetchone()[0] == 2000
        assert db.execute('SELECT COUNT(*) FROM charge_sessions WHERE activeKey=1').fetchone()[0] == 0
        if version >= 2:
            assert db.execute('SELECT COUNT(*) FROM app_energy_stats').fetchone()[0] == 1
        db.execute("UPDATE charge_sessions SET activeKey=1 WHERE sessionId='finished'")
        try:
            db.execute("UPDATE charge_sessions SET activeKey=1 WHERE sessionId='interrupted'")
        except sqlite3.IntegrityError:
            pass
        else:
            raise AssertionError('Active session uniqueness was not enforced')
        print(f'PASS SQLite schema, indices, preserved rows, sentinels and active uniqueness: {version} → 4')
