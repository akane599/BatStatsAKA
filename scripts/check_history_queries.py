#!/usr/bin/env python3
"""Exercise actual Room DAO SQL on host SQLite; not a substitute for Android Room tests."""
import json
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
dao = (root / 'app/src/main/java/app/batstats/battery/data/db/Dao.kt').read_text()
schema = json.loads((root / 'app/schemas/app.batstats.battery.data.db.BatteryDatabase/4.json').read_text())['database']

def query(method, interface=None):
    section = dao if interface is None else dao.split('interface ' + interface + ' {')[1].split('\n}\n')[0]
    found = re.search(r'@Query\("([^"\n]+)"\)\s+(?:suspend )?fun ' + method + r'\(', section)
    assert found, 'Cannot locate actual DAO query: ' + method
    return found[1]

with sqlite3.connect(':memory:') as db:
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for at, origin, observation, row_id in [(1500, 'BatteryManager', 'local', 1),
                                           (1600, 'import:BatteryManager', 'import:foreign', -100),
                                           (1700, 'legacy', None, 2)]:
        db.execute('INSERT INTO battery_samples(id,timestamp,status,screenOn,source,observationId,elapsedMs) VALUES(?,?,3,1,?,?,?)',
                   (row_id, at, origin, observation, at))
    rows = db.execute(query('chartSamples'), {'from': 0, 'to': 3000, 'bucketMs': 1}).fetchall()
    assert len(rows) == 1 and rows[0][0] == 1, 'Local chart mixed imported or legacy data'
    assert db.execute('SELECT COUNT(*) FROM battery_samples').fetchone()[0] == 3, 'Filtering deleted history'
    db.execute('INSERT INTO battery_samples(timestamp,status,screenOn) VALUES(1800,3,1)')
    assert db.execute('SELECT id FROM battery_samples WHERE timestamp=1800').fetchone()[0] > 0, 'Imported IDs corrupted local AUTOINCREMENT'
    db.commit()
    try:
        with db:
            db.execute('INSERT INTO battery_samples(timestamp,status,screenOn,observationId,elapsedMs) VALUES(1900,3,1,\'new\',1900)')
            db.execute('INSERT INTO battery_samples(timestamp,status,screenOn,observationId,elapsedMs) VALUES(2000,3,1,\'new\',1900)')
    except sqlite3.IntegrityError:
        pass
    else:
        raise AssertionError('Observed-point uniqueness not enforced')
    assert db.execute("SELECT COUNT(*) FROM battery_samples WHERE observationId='new'").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM battery_samples").fetchone()[0] == 4, "Rollback removed pre-existing records"
    for i in range(20):
        db.execute("INSERT INTO charge_sessions(sessionId,type,startTime,endTime,activeKey) VALUES(?,'DISCHARGE',?,?,?)",
                   (str(i), i * 1000, None if i == 0 else (i + 1) * 1000, 1 if i == 0 else None))
    overlaps = db.execute(query('sessionsBetween'), {'from': 1500, 'to': 1600}).fetchall()
    assert [row[0] for row in overlaps] == ['1'], 'Overlapping session selection was incorrect'
    db.execute(query('boundStorage', 'SessionDao'), {'limit': 3})
    assert db.execute('SELECT COUNT(*) FROM charge_sessions').fetchone()[0] == 3, 'Session bound not enforced'
    assert db.execute('SELECT sessionId FROM charge_sessions WHERE activeKey=1').fetchone()[0] == '0', 'Old active session was evicted'
print('PASS actual DAO SQL: source-separated charts, positive local IDs, unique points/rollback, overlapping windows, bounded sessions retaining active state')

with sqlite3.connect(':memory:') as db:
    db.row_factory = sqlite3.Row
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for i in range(125):
        db.execute("INSERT INTO charge_sessions(sessionId,type,startTime,endTime,source) VALUES(?,?,?,?,?)",
                   (str(i), 'CHARGE' if i == 0 else 'DISCHARGE', i * 1000, i * 1000, 'import:saved_origin' if i == 0 else 'BatteryManager observed interval'))
    args = {'type': None, 'query': '', 'limit': 51}
    assert len(db.execute(query('filteredSessions'), args).fetchall()) == 51
    args['limit'] = 151
    assert len(db.execute(query('filteredSessions'), args).fetchall()) == 125, 'Older history is inaccessible'
    args.update({'type': 'CHARGE', 'query': 'SAVED_', 'limit': 51})
    assert [r['sessionId'] for r in db.execute(query('filteredSessions'), args)] == ['0'], 'Filter only searched recent page or mishandled literal underscore'
    for i in range(1001):
        db.execute("INSERT INTO battery_samples(timestamp,status,screenOn,sessionId,observationId,currentNowUa,voltageMv,temperatureDeciC,boundaryReason) VALUES(?,3,1,'chart','o',?,4000,250,?)",
                   (i * 1000, None if i == 400 else -123, 'interrupted' if i == 501 else None))
    db.execute("INSERT INTO battery_samples(timestamp,status,screenOn,sessionId,currentNowUa) VALUES(900000,3,1,'foreign',999999)")
    rows = db.execute(query('sessionChartSamples'), {'sessionId': 'chart', 'from': 0, 'to': 1000000, 'bucketMs': 1000000 // 360 + 1}).fetchall()
    assert len(rows) <= 361, 'Session chart was unbounded'
    assert all(r['currentNowUa'] != 999999 for r in rows), 'Session chart mixed unrelated time-overlapping readings'
    assert sum(r['discontinuity'] for r in rows) >= 2, 'Downsampling hid missing samples or explicit gaps'
print('PASS actual history UI queries: paging/filter across125 records, bounded session-only charts preserving discontinuities')
