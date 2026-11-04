import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'cms_parser.dart';
import 'models.dart';

/// Persistent preferences and backup serializer compatible with Android `Prefs.java`.
class StorageService {
  static const String keyOrigin = 'origin';
  static const String keyRawConfig = 'config_raw_json';
  static const String keySelectedSite = 'selected_site';
  static const String keyWatchHistory = 'watch_history';
  static const String keyFavorites = 'favorites';
  static const String keySearchHistory = 'global_search_history';
  static const String keyEpisodesDescending = 'episodes_descending';
  static const String keyPlaySpeed = 'play_speed';
  static const String keyAutoNext = 'auto_next';

  final SharedPreferences _prefs;

  StorageService(this._prefs);

  static Future<StorageService> init() async {
    final prefs = await SharedPreferences.getInstance();
    return StorageService(prefs);
  }

  String get configOrigin => _prefs.getString(keyOrigin) ?? '';
  String get rawConfig => _prefs.getString(keyRawConfig) ?? '';
  String get selectedSiteKey => _prefs.getString(keySelectedSite) ?? '';
  bool get episodesDescending => _prefs.getBool(keyEpisodesDescending) ?? false;
  double get playSpeed => (_prefs.getDouble(keyPlaySpeed) ?? 1.0).clamp(0.5, 2.0);
  bool get autoNext => _prefs.getBool(keyAutoNext) ?? true;

  CmsConfig? loadSavedConfig() {
    final raw = rawConfig;
    if (raw.trim().isEmpty) return null;
    try {
      return CmsParser.parseConfig(raw, origin: configOrigin);
    } catch (_) {
      return null;
    }
  }

  Future<CmsConfig> saveConfig(String rawJson, {String origin = ''}) async {
    final parsed = CmsParser.parseConfig(rawJson, origin: origin);
    await _prefs.setString(keyRawConfig, rawJson);
    await _prefs.setString(keyOrigin, origin.trim());
    if (parsed.supportedSites.isNotEmpty &&
        !parsed.supportedSites.any((s) => s.key == selectedSiteKey)) {
      await _prefs.setString(keySelectedSite, parsed.supportedSites.first.key);
    }
    return parsed;
  }

  Future<void> setSelectedSiteKey(String key) async {
    await _prefs.setString(keySelectedSite, key);
  }

  Future<void> setEpisodesDescending(bool value) async {
    await _prefs.setBool(keyEpisodesDescending, value);
  }

  Future<void> setPlaySpeed(double speed) async {
    await _prefs.setDouble(keyPlaySpeed, speed.clamp(0.5, 2.0));
  }

  Future<void> setAutoNext(bool value) async {
    await _prefs.setBool(keyAutoNext, value);
  }

  List<WatchHistoryEntry> getWatchHistory() {
    final raw = _prefs.getString(keyWatchHistory) ?? '[]';
    try {
      final list = jsonDecode(raw);
      if (list is! List) return const [];
      return list
          .whereType<Map>()
          .map((m) => WatchHistoryEntry.fromJson(Map<String, dynamic>.from(m)))
          .toList(growable: false);
    } catch (_) {
      return const [];
    }
  }

  Future<void> upsertWatchHistory(WatchHistoryEntry entry) async {
    final updated = CmsParser.upsertHistory(getWatchHistory(), entry);
    await _prefs.setString(
      keyWatchHistory,
      jsonEncode(updated.map((e) => e.toJson()).toList()),
    );
  }

  Future<void> clearWatchHistory() async {
    await _prefs.setString(keyWatchHistory, '[]');
  }

  List<FavoriteEntry> getFavorites() {
    final raw = _prefs.getString(keyFavorites) ?? '[]';
    try {
      final list = jsonDecode(raw);
      if (list is! List) return const [];
      return list
          .whereType<Map>()
          .map((m) => FavoriteEntry.fromJson(Map<String, dynamic>.from(m)))
          .toList(growable: false);
    } catch (_) {
      return const [];
    }
  }

  bool isFavorite(String siteKey, String videoId) {
    final target = '$siteKey\n$videoId';
    return getFavorites().any((f) => f.key == target);
  }

  Future<bool> toggleFavorite(CmsSite site, CmsVideo video) async {
    final current = getFavorites().toList();
    final targetKey = '${site.key}\n${video.id}';
    final existingIdx = current.indexWhere((f) => f.key == targetKey);
    bool nowFav;
    if (existingIdx >= 0) {
      current.removeAt(existingIdx);
      nowFav = false;
    } else {
      current.insert(
        0,
        FavoriteEntry(
          site: site.key,
          id: video.id,
          title: video.title,
          poster: video.poster,
          remarks: video.remarks,
          updated: DateTime.now().millisecondsSinceEpoch,
        ),
      );
      if (current.length > CmsParser.maxFavoriteEntries) {
        current.removeRange(CmsParser.maxFavoriteEntries, current.length);
      }
      nowFav = true;
    }
    await _prefs.setString(
      keyFavorites,
      jsonEncode(current.map((e) => e.toJson()).toList()),
    );
    return nowFav;
  }

  List<String> getSearchHistory() {
    final raw = _prefs.getString(keySearchHistory) ?? '[]';
    try {
      final list = jsonDecode(raw);
      if (list is! List) return const [];
      return list.map((e) => e.toString()).where((s) => s.isNotEmpty).toList();
    } catch (_) {
      return const [];
    }
  }

  Future<void> addSearchKeyword(String keyword) async {
    final trimmed = keyword.trim();
    if (trimmed.isEmpty) return;
    final list = getSearchHistory().where((k) => k != trimmed).toList();
    list.insert(0, trimmed);
    if (list.length > 16) list.removeRange(16, list.length);
    await _prefs.setString(keySearchHistory, jsonEncode(list));
  }

  /// Exports a backup JSON string 100% compatible with Android `SettingsScreen.exportBackup`.
  String exportBackupJson() {
    final payload = <String, dynamic>{
      'schema': 'marco-movie-backup',
      'version': 1,
      'exportedAt': DateTime.now().millisecondsSinceEpoch,
      'origin': configOrigin,
      'config': rawConfig,
      'settings': <String, dynamic>{
        keySelectedSite: selectedSiteKey,
        keyWatchHistory: _prefs.getString(keyWatchHistory) ?? '[]',
        keyFavorites: _prefs.getString(keyFavorites) ?? '[]',
        keySearchHistory: _prefs.getString(keySearchHistory) ?? '[]',
        keyEpisodesDescending: episodesDescending,
        keyPlaySpeed: playSpeed,
        keyAutoNext: autoNext,
      },
    };
    return const JsonEncoder.withIndent('  ').convert(payload);
  }

  /// Imports a backup JSON generated by either Android TV/Phone or Windows/Mac/iOS.
  Future<CmsConfig?> importBackupJson(String rawBackup) async {
    final decoded = jsonDecode(rawBackup);
    if (decoded is! Map<String, dynamic> ||
        decoded['schema'] != 'marco-movie-backup') {
      throw const FormatException('不是有效的 Marco Movie 备份文件');
    }
    final origin = (decoded['origin'] ?? '').toString();
    final cfgText = (decoded['config'] ?? '').toString();
    CmsConfig? parsedConfig;
    if (cfgText.trim().isNotEmpty) {
      parsedConfig = await saveConfig(cfgText, origin: origin);
    }
    final settings = decoded['settings'];
    if (settings is Map) {
      if (settings[keySelectedSite] is String) {
        await setSelectedSiteKey(settings[keySelectedSite] as String);
      }
      if (settings[keyWatchHistory] is String) {
        await _prefs.setString(
            keyWatchHistory, settings[keyWatchHistory] as String);
      }
      if (settings[keyFavorites] is String) {
        await _prefs.setString(keyFavorites, settings[keyFavorites] as String);
      }
      if (settings[keySearchHistory] is String) {
        await _prefs.setString(
            keySearchHistory, settings[keySearchHistory] as String);
      }
      if (settings[keyEpisodesDescending] is bool) {
        await setEpisodesDescending(settings[keyEpisodesDescending] as bool);
      }
      if (settings[keyPlaySpeed] is num) {
        await setPlaySpeed((settings[keyPlaySpeed] as num).toDouble());
      }
      if (settings[keyAutoNext] is bool) {
        await setAutoNext(settings[keyAutoNext] as bool);
      }
    }
    return parsedConfig;
  }
}
