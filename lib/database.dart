import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as path;
import 'package:sqflite/sqflite.dart';

import 'models.dart';

/// 心情记录数据库。
///
/// 直接沿用原 Room 版的 `mood_diary.db` / `mood_entries` 表结构与索引，
/// 从混编版（或更早的 Kotlin 版）覆盖安装后，**历史记录无缝继承**。
class MoodDatabase {
  MoodDatabase._();

  static Database? _db;

  static Future<Database> get instance async {
    _db ??= await _open();
    return _db!;
  }

  static Future<Database> _open() async {
    final dir = await getDatabasesPath();
    final dbPath = path.join(dir, 'mood_diary.db');
    return openDatabase(
      dbPath,
      version: 2,
      onCreate: (db, version) async {
        // v2 表结构（与 Room @Entity 等价）
        await db.execute('''
          CREATE TABLE mood_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            date TEXT NOT NULL,
            hour INTEGER NOT NULL,
            moodId INTEGER NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            updatedAt INTEGER NOT NULL
          )
        ''');
        await db.execute(
            'CREATE UNIQUE INDEX index_mood_entries_date_hour ON mood_entries (date, hour)');
      },
      onUpgrade: (db, oldV, newV) async {
        // 仅对 v1 保留破坏性迁移：v1 的建表语句已无处可考，
        // 历史版本（Room fallbackToDestructiveMigrationFrom(1)）正是这样处理的。
        // v2 往后的任何升级都必须显式迁移，绝不清空用户日记。
        if (oldV < 2) {
          await db.execute('DROP TABLE IF EXISTS mood_entries');
          await onCreate(db, newV);
        }
      },
    );
  }

  static Future<void> onCreate(Database db, int version) async {
    await db.execute('''
      CREATE TABLE mood_entries (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        date TEXT NOT NULL,
        hour INTEGER NOT NULL,
        moodId INTEGER NOT NULL,
        note TEXT NOT NULL DEFAULT '',
        updatedAt INTEGER NOT NULL
      )
    ''');
    await db.execute(
        'CREATE UNIQUE INDEX IF NOT EXISTS index_mood_entries_date_hour ON mood_entries (date, hour)');
  }
}

/// 记录仓库：可观察（ChangeNotifier），任何写操作后通知监听者重载。
///
/// 关键约定：任何路径都不得静默删除已有记录。
/// 保存前显式查冲突（同一 (date, hour) 且不是同一条记录），
/// 由 UI 询问用户是否覆盖，而不是依赖 REPLACE 静默吞掉旧行。
class MoodRepository extends ChangeNotifier {
  MoodRepository();

  List<MoodEntry> _entries = [];
  bool _loaded = false;

  List<MoodEntry> get entries => _entries;
  bool get loaded => _loaded;

  Future<void> load() async {
    final db = await MoodDatabase.instance;
    final rows = await db.query('mood_entries', orderBy: 'date DESC, hour DESC');
    _entries = rows.map((r) => MoodEntry.fromMap(r)).toList();
    _loaded = true;
    notifyListeners();
  }

  MoodEntry? findByDateHour(String date, int hour) {
    for (final e in _entries) {
      if (e.date == date && e.hour == hour) return e;
    }
    return null;
  }

  MoodEntry? _findInDb(List<Map<String, Object?>> rows, String date, int hour) {
    for (final r in rows) {
      final e = MoodEntry.fromMap(r);
      if (e.date == date && e.hour == hour) return e;
    }
    return null;
  }

  /// 保存（插入或更新）。目标时段被另一条记录占用时返回冲突，不写入。
  Future<SaveOutcome> save(DateTime date, int hour, int moodId, String note,
      MoodEntry? old) async {
    final db = await MoodDatabase.instance;
    final dateStr = _fmtDate(date);
    final trimmed = note.trim();
    if (trimmed.length > kMaxNoteLength) trimmed.substring(0, kMaxNoteLength);

    // 与原版一致：先在事务内查冲突再写入（编辑自身不算冲突）
    final outcome = await db.transaction<SaveOutcome>((txn) async {
      final rows =
          await txn.query('mood_entries', where: 'date = ? AND hour = ?',
              whereArgs: [dateStr, hour]);
      final existing = _findInDb(rows, dateStr, hour);
      if (existing != null && existing.id != old?.id) {
        return Conflict(existing);
      }
      final entry = MoodEntry(
        id: old?.id ?? 0,
        date: dateStr,
        hour: hour,
        moodId: moodId,
        note: trimmed,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      if (old == null) {
        await txn.insert('mood_entries', entry.toMap(),
            conflictAlgorithm: ConflictAlgorithm.abort);
      } else {
        await txn
            .update('mood_entries', entry.toMap(), where: 'id = ?', whereArgs: [old.id]);
      }
      return const Saved();
    });
    if (outcome is Saved) await load();
    return outcome;
  }

  /// 仅在用户于冲突弹窗中确认「覆盖」后调用：先删被覆盖的记录，再写入新记录。
  Future<void> overwrite(DateTime date, int hour, int moodId, String note,
      MoodEntry? old, MoodEntry conflict) async {
    final db = await MoodDatabase.instance;
    final dateStr = _fmtDate(date);
    final trimmed = note.trim();
    if (trimmed.length > kMaxNoteLength) trimmed.substring(0, kMaxNoteLength);
    await db.transaction((txn) async {
      await txn.delete('mood_entries', where: 'id = ?', whereArgs: [conflict.id]);
      final entry = MoodEntry(
        id: old?.id ?? 0,
        date: dateStr,
        hour: hour,
        moodId: moodId,
        note: trimmed,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      if (old == null) {
        await txn.insert('mood_entries', entry.toMap(),
            conflictAlgorithm: ConflictAlgorithm.abort);
      } else {
        await txn
            .update('mood_entries', entry.toMap(), where: 'id = ?', whereArgs: [old.id]);
      }
    });
    await load();
  }

  Future<void> delete(MoodEntry entry) async {
    final db = await MoodDatabase.instance;
    await db.delete('mood_entries', where: 'id = ?', whereArgs: [entry.id]);
    await load();
  }

  /// 一次清空：单条 SQL
  Future<void> deleteAll() async {
    final db = await MoodDatabase.instance;
    await db.delete('mood_entries');
    await load();
  }

  /// 一次性数据修复：把引用了已删除心情的记录，永久改写为当前目录中就近的心情 id。
  /// 显示不再依赖兜底规则，统计永不漂移（与原版 onCreate 修复逻辑一致）。
  Future<void> repairUnknownMoods() async {
    final db = await MoodDatabase.instance;
    final validIds = moods.map((m) => m.id).toList();
    final broken = await db.rawQuery(
        'SELECT * FROM mood_entries WHERE moodId NOT IN (${List.filled(validIds.length, '?').join(',')})',
        validIds);
    for (final row in broken) {
      final e = MoodEntry.fromMap(row);
      await db.update('mood_entries', {'moodId': moodOf(e.moodId).id},
          where: 'id = ?', whereArgs: [e.id]);
    }
    if (broken.isNotEmpty) await load();
  }

  /// 通知快捷记录（后台 isolate 调用）：目标时段已有记录则不覆盖，静默跳过。
  static Future<void> recordQuickMood(int hour, int moodId) async {
    final db = await MoodDatabase.instance;
    final now = DateTime.now();
    final dateStr = _fmtDate(now);
    final rows = await db.query('mood_entries',
        where: 'date = ? AND hour = ?', whereArgs: [dateStr, hour]);
    if (rows.isNotEmpty) return; // 已有记录，不覆盖
    await db.insert(
        'mood_entries',
        MoodEntry(
          id: 0,
          date: dateStr,
          hour: hour,
          moodId: moodId,
          updatedAt: now.millisecondsSinceEpoch,
        ).toMap(),
        conflictAlgorithm: ConflictAlgorithm.abort);
  }

  static String _fmtDate(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}
