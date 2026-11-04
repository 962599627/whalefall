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
  static const String keyDepots = 'saved_depots';
  static const String keyActiveDepotName = 'active_depot_name';
  final SharedPreferences _prefs;
  StorageService(this._prefs);
  static Future<StorageService> init() async {
    final prefs = await SharedPreferences.getInstance();
    return StorageService(prefs);
  }
  String get configOrigin => _prefs.getString(keyOrigin) ?? '';
  String get rawConfig => _prefs.getString(keyRawConfig) ?? '';
  String get selectedSiteKey => _prefs.getString(keySelectedSite) ?? '';
  String get activeDepotName => _prefs.getString(keyActiveDepotName) ?? '';
  bool get episodesDescending => _prefs.getBool(keyEpisodesDescending) ?? false;
  double get playSpeed =>
      (_prefs.getDouble(keyPlaySpeed) ?? 1.0).clamp(0.5, 2.0);
  bool get autoNext => _prefs.getBool(keyAutoNext) ?? true;
  List<CmsDepot> getSavedDepots() {
    final raw = _prefs.getString(keyDepots) ?? '[]';
    try {
      final list = jsonDecode(raw);
      if (list is! List) return const [];
      return list
          .whereType<Map>()
          .map((m) => CmsDepot.fromJson(Map<String, dynamic>.from(m)))
          .where((d) => d.url.isNotEmpty)
          .toList(growable: false);
    } catch (_) {
      return const [];
    }
  }
  CmsConfig? loadSavedConfig() {
    final raw = rawConfig;
    if (raw.trim().isEmpty) return null;
    try {
      final parsed = CmsParser.parseConfig(raw, origin: configOrigin);
      final savedDepots = getSavedDepots();
      if (parsed.depots.isEmpty && savedDepots.isNotEmpty) {
        return CmsConfig(
          sites: parsed.sites,
          lives: parsed.lives,
          depots: savedDepots,
          spider: parsed.spider,
        );
      }
      return parsed;
    } catch (_) {
      return null;
    }
  }
  Future<CmsConfig> saveConfig(
    String rawJson, {
    String origin = '',
    List<CmsDepot>? depots,
    String? activeDepotName,
  }) async {
    final parsed = CmsParser.parseConfig(rawJson, origin: origin);
    await _prefs.setString(keyRawConfig, rawJson);
    await _prefs.setString(keyOrigin, origin.trim());
    final effectiveDepots = depots ?? parsed.depots;
    if (effectiveDepots.isNotEmpty) {
      await _prefs.setString(
        keyDepots,
        jsonEncode(effectiveDepots.map((d) => d.toJson()).toList()),
      );
    } else if (depots != null && depots.isEmpty) {
      await _prefs.setString(keyDepots, '[]');
    }
    if (activeDepotName != null) {
      await _prefs.setString(keyActiveDepotName, activeDepotName);
    }
    if (parsed.supportedSites.isNotEmpty &&
        !parsed.supportedSites.any((s) => s.key == selectedSiteKey)) {
      await _prefs.setString(keySelectedSite, parsed.supportedSites.first.key);
    } else if (parsed.supportedSites.isEmpty && parsed.sites.isNotEmpty) {
      await _prefs.setString(keySelectedSite, parsed.sites.first.key);
    }
    return CmsConfig(
      sites: parsed.sites,
      lives: parsed.lives,
      depots: effectiveDepots,
      spider: parsed.spider,
    );
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
  Future<void> clearSearchHistory() async {
    await _prefs.setString(keySearchHistory, '[]');
  }
  /// Exports a backup JSON string compatible with both Android (`MarcoMovie-1`) and Flutter.
  String exportBackupJson() {
    return CmsParser.encodeBackupPayload(
      origin: configOrigin,
      rawConfig: rawConfig,
      settings: <String, dynamic>{
        keySelectedSite: selectedSiteKey,
        keyWatchHistory: _prefs.getString(keyWatchHistory) ?? '[]',
        keyFavorites: _prefs.getString(keyFavorites) ?? '[]',
        keySearchHistory: _prefs.getString(keySearchHistory) ?? '[]',
        keyEpisodesDescending: episodesDescending,
        keyPlaySpeed: playSpeed,
        keyAutoNext: autoNext,
      },
    );
  }
  static String? _normalizeJsonArraySetting(dynamic value) {
    if (value is List) {
      return jsonEncode(value);
    }
    if (value is String) {
      final trimmed = value.trim();
      if (trimmed.isEmpty) return '[]';
      final decoded = jsonDecode(trimmed);
      if (decoded is List) return jsonEncode(decoded);
    }
    return null;
  }
  /// Imports a backup JSON generated by either Android TV/Phone or Windows/Mac/iOS.
  /// Validates all fields before mutating preferences so failed restores preserve existing state.
  Future<CmsConfig?> importBackupJson(String rawBackup) async {
    final decoded = CmsParser.decodeBackupPayload(rawBackup);
    final settings = decoded.settings;
    final nextHistory = settings.containsKey(keyWatchHistory)
        ? _normalizeJsonArraySetting(settings[keyWatchHistory])
        : null;
    final nextFavorites = settings.containsKey(keyFavorites)
        ? _normalizeJsonArraySetting(settings[keyFavorites])
        : null;
    final nextSearch = settings.containsKey(keySearchHistory)
        ? _normalizeJsonArraySetting(settings[keySearchHistory])
        : null;
    CmsConfig? parsedConfig = decoded.parsedConfig;
    if (decoded.rawConfig.isNotEmpty) {
      await _prefs.setString(keyRawConfig, decoded.rawConfig);
      await _prefs.setString(keyOrigin, decoded.origin);
      if (parsedConfig != null &&
          parsedConfig.supportedSites.isNotEmpty &&
          !parsedConfig.supportedSites.any((s) => s.key == selectedSiteKey)) {
        await _prefs.setString(
          keySelectedSite,
          parsedConfig.supportedSites.first.key,
        );
      }
    }
    if (settings[keySelectedSite] is String) {
      await setSelectedSiteKey(settings[keySelectedSite] as String);
    }
    if (nextHistory != null) {
      await _prefs.setString(keyWatchHistory, nextHistory);
    }
    if (nextFavorites != null) {
      await _prefs.setString(keyFavorites, nextFavorites);
    }
    if (nextSearch != null) {
      await _prefs.setString(keySearchHistory, nextSearch);
    }
    if (settings[keyEpisodesDescending] is bool) {
      await setEpisodesDescending(settings[keyEpisodesDescending] as bool);
    }
    if (settings[keyPlaySpeed] is num) {
      final speed = (settings[keyPlaySpeed] as num).toDouble();
      if (speed >= 0.5 && speed <= 2.0) {
        await setPlaySpeed(speed);
      }
    }
    if (settings[keyAutoNext] is bool) {
      await setAutoNext(settings[keyAutoNext] as bool);
    }
    return parsedConfig;
  }
}
