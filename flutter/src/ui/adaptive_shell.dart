import 'dart:async';
import 'dart:io';
import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../core/cms_client.dart';
import '../core/cms_parser.dart';
import '../core/csp_bridge.dart';
import '../core/download_service.dart';
import '../core/models.dart';
import '../core/netdisk_service.dart';
import '../core/storage_service.dart';
import 'apple_theme.dart';
import 'detail_screen.dart';
import 'netdisk_sheet.dart';
import 'player_screen.dart';
import 'poster_image.dart';
/// Apple HIG adaptive shell:
/// - macOS / iPadOS / Desktop (`width >= 700`): Translucent sidebar with traffic-light inset & source card
/// - iOS (`width < 700`): Frosted-glass top navigation bar and translucent Cupertino bottom tab bar
class AdaptiveShell extends StatefulWidget {
  final StorageService storage;
  final CmsClient client;
  const AdaptiveShell({
    super.key,
    required this.storage,
    required this.client,
  });
  @override
  State<AdaptiveShell> createState() => _AdaptiveShellState();
}
class _AdaptiveShellState extends State<AdaptiveShell> {
  int _tabIndex = 0;
  CmsConfig? _config;
  CmsSite? _activeSite;
  List<CmsCategory> _categories = const [];
  String _selectedCategoryId = '';
  String _primaryTab = 'recommend';
  bool _filterExpanded = false;
  String _filterSubCategoryId = '';
  String _filterGenreLabel = '';
  String _filterArea = '';
  String _filterYear = '';
  List<CmsVideo> _rawCategoryVideos = const [];
  List<CmsVideo> _homeVideos = const [];
  int _currentPage = 1;
  int _pageCount = 1;
  bool _homeLoading = false;
  String? _homeError;
  int _homeEpoch = 0;
  int _categoryEpoch = 0;
  // 5-tap logo privacy state
  int _logoTapCount = 0;
  DateTime? _lastLogoTapAt;
  bool _privateSessionUnlocked = false;
  // Search state
  final TextEditingController _searchController = TextEditingController();
  final List<(CmsSite, CmsVideo)> _searchResults = [];
  bool _searching = false;
  int _searchEpoch = 0;
  StreamSubscription<(CmsSite, List<CmsVideo>)>? _searchSubscription;
  VoidCallback? _searchSheetListener;
  // Live state
  int _selectedLiveSource = 0;
  List<LiveGroup> _liveGroups = const [];
  bool _liveLoading = false;
  String? _liveError;
  int _liveEpoch = 0;
  // Favorites / History segmented index
  int _librarySegment = 0;
  bool get _isMacOS => !kIsWeb && Platform.isMacOS;
  CmsFilter get _currentFilter => CmsFilter(
        primaryTab: _primaryTab,
        categoryId: _filterSubCategoryId,
        genreLabel: _filterGenreLabel,
        area: _filterArea,
        year: _filterYear,
      );
  List<CmsSite> get _visibleSupportedSites {
    final cfg = _config;
    if (cfg == null) return const [];
    return _privateSessionUnlocked
        ? cfg.supportedSites
        : cfg.publicSupportedSites;
  }
  List<LiveSource> get _visibleLives {
    final cfg = _config;
    if (cfg == null) return const [];
    return _privateSessionUnlocked ? cfg.lives : cfg.publicLives;
  }
  @override
  void initState() {
    super.initState();
    _reloadSavedConfig();
  }
  @override
  void dispose() {
    _searchSubscription?.cancel();
    _searchController.dispose();
    super.dispose();
  }
  void _reloadSavedConfig() {
    final saved = widget.storage.loadSavedConfig();
    _config = saved;
    final availableSites = saved == null
        ? const <CmsSite>[]
        : (_privateSessionUnlocked
            ? saved.supportedSites
            : (saved.publicSupportedSites.isNotEmpty
                ? saved.publicSupportedSites
                : saved.supportedSites.where((s) => !s.isPrivate).toList()));
    if (availableSites.isNotEmpty) {
      final preferredKey = widget.storage.selectedSiteKey;
      _activeSite = availableSites.firstWhere(
        (s) => s.key == preferredKey,
        orElse: () => availableSites.first,
      );
      _loadSiteHome();
    } else {
      _activeSite = null;
      _categories = const [];
      _rawCategoryVideos = const [];
      _homeVideos = const [];
      _homeError = null;
    }
    final lives = _visibleLives;
    if (lives.isNotEmpty) {
      _loadLivePlaylist(0);
    } else {
      _liveGroups = const [];
    }
    if (mounted) setState(() {});
  }
  Future<void> _loadSiteHome() async {
    final site = _activeSite;
    if (site == null) return;
    final epoch = ++_homeEpoch;
    ++_categoryEpoch;
    setState(() {
      _homeLoading = true;
      _homeError = null;
      _primaryTab = 'recommend';
      _selectedCategoryId = '';
      _filterSubCategoryId = '';
      _filterGenreLabel = '';
      _filterArea = '';
      _filterYear = '';
      _currentPage = 1;
    });
    try {
      final (cats, listing) = await widget.client.fetchHome(site);
      if (!mounted || epoch != _homeEpoch || _activeSite?.key != site.key) {
        return;
      }
      setState(() {
        _categories = cats;
        _rawCategoryVideos = listing.videos;
        _homeVideos = CmsParser.filterVideos(listing.videos, _currentFilter);
        _currentPage = listing.page;
        _pageCount = listing.pageCount;
        _homeLoading = false;
      });
    } catch (e) {
      if (!mounted || epoch != _homeEpoch || _activeSite?.key != site.key) {
        return;
      }
      setState(() {
        _homeLoading = false;
        _homeError = '读取片源「${site.name}」失败：$e';
      });
    }
  }
  Future<void> _selectPrimaryTab(String tabKey) async {
    if (tabKey == 'recommend') {
      setState(() {
        _primaryTab = 'recommend';
        _filterSubCategoryId = '';
        _filterGenreLabel = '';
      });
      await _reloadFilteredListing(page: 1);
      return;
    }
    final resolved = CmsParser.resolvePrimaryTabCategory(_categories, tabKey);
    setState(() {
      _primaryTab = tabKey;
      _filterSubCategoryId = '';
      _filterGenreLabel = '';
      _selectedCategoryId = resolved.categoryId;
    });
    await _reloadFilteredListing(page: 1);
  }
  Future<void> _reloadFilteredListing({int page = 1}) async {
    final site = _activeSite;
    if (site == null) return;
    final epoch = ++_categoryEpoch;
    final filter = _currentFilter;
    String targetCategoryId = '';
    List<String> targetChildIds = const [];
    if (_filterSubCategoryId.isNotEmpty) {
      targetCategoryId = _filterSubCategoryId;
      targetChildIds =
          CmsParser.childCategoryIds(_categories, _filterSubCategoryId);
    } else if (_primaryTab != 'recommend') {
      final resolved =
          CmsParser.resolvePrimaryTabCategory(_categories, _primaryTab);
      targetCategoryId = resolved.categoryId;
      targetChildIds = resolved.childIds;
    }
    setState(() {
      _homeLoading = true;
      _homeError = null;
      _selectedCategoryId = targetCategoryId;
    });
    try {
      CmsListing listing;
      if (targetCategoryId.isEmpty && _primaryTab == 'recommend' && page == 1) {
        final (cats, homeListing) = await widget.client.fetchHome(site);
        if (_categories.isEmpty && cats.isNotEmpty) {
          _categories = cats;
        }
        listing = homeListing;
      } else {
        final effectiveCatId = targetCategoryId.isNotEmpty
            ? targetCategoryId
            : (_categories.isNotEmpty ? _categories.first.id : '');
        listing = await widget.client.fetchCategory(
          site,
          effectiveCatId,
          page,
          childIds: targetChildIds,
          filter: filter,
        );
      }
      if (!mounted || epoch != _categoryEpoch || _activeSite?.key != site.key) {
        return;
      }
      final combinedRaw = page == 1
          ? listing.videos
          : [..._rawCategoryVideos, ...listing.videos];
      var filteredVideos = CmsParser.filterVideos(combinedRaw, filter);
      // If client-side filter on page 1 matched nothing and more pages exist, probe page 2 automatically
      if (filteredVideos.isEmpty &&
          filter.hasSubFilter &&
          page == 1 &&
          listing.pageCount > 1 &&
          targetCategoryId.isNotEmpty) {
        try {
          final page2 = await widget.client.fetchCategory(
            site,
            targetCategoryId,
            2,
            childIds: targetChildIds,
            filter: filter,
          );
          final merged2 = [...combinedRaw, ...page2.videos];
          final filtered2 = CmsParser.filterVideos(merged2, filter);
          if (filtered2.isNotEmpty) {
            filteredVideos = filtered2;
          }
        } catch (_) {}
      }
      setState(() {
        _rawCategoryVideos = combinedRaw;
        _homeVideos = filteredVideos;
        _currentPage = listing.page;
        _pageCount = listing.pageCount;
        _homeLoading = false;
      });
    } catch (e) {
      if (!mounted || epoch != _categoryEpoch || _activeSite?.key != site.key) {
        return;
      }
      setState(() {
        _homeLoading = false;
        _homeError = '加载分类失败：$e';
      });
    }
  }
  Future<void> _loadCategory(String categoryId, {int page = 1}) async {
    _filterSubCategoryId = categoryId;
    await _reloadFilteredListing(page: page);
  }
  Future<void> _runGlobalSearch(String keyword) async {
    final trimmed = keyword.trim();
    if (trimmed.isEmpty || _config == null) return;
    final epoch = ++_searchEpoch;
    await _searchSubscription?.cancel();
    _searchSubscription = null;
    await widget.storage.addSearchKeyword(trimmed);
    if (!mounted || epoch != _searchEpoch) return;
    setState(() {
      _searching = true;
      _searchResults.clear();
    });
    _searchSheetListener?.call();
    _searchSubscription = widget.client
        .searchAcrossSites(_visibleSupportedSites, trimmed)
        .listen(
          (batch) {
            if (!mounted || epoch != _searchEpoch) return;
            final (site, videos) = batch;
            setState(() {
              for (final v in videos) {
                _searchResults.add((site, v));
              }
            });
            _searchSheetListener?.call();
          },
          onError: (_) {
            if (mounted && epoch == _searchEpoch) {
              setState(() => _searching = false);
              _searchSheetListener?.call();
            }
          },
          onDone: () {
            if (mounted && epoch == _searchEpoch) {
              setState(() => _searching = false);
              _searchSheetListener?.call();
            }
          },
        );
  }
  Future<void> _loadLivePlaylist(int sourceIndex) async {
    final lives = _visibleLives;
    if (sourceIndex < 0 || sourceIndex >= lives.length) return;
    final epoch = ++_liveEpoch;
    setState(() {
      _selectedLiveSource = sourceIndex;
      _liveLoading = true;
      _liveError = null;
    });
    try {
      final groups = await widget.client.fetchLiveGroups(lives[sourceIndex]);
      if (!mounted ||
          epoch != _liveEpoch ||
          _selectedLiveSource != sourceIndex) {
        return;
      }
      setState(() {
        _liveGroups = groups;
        _liveLoading = false;
      });
    } catch (e) {
      if (!mounted ||
          epoch != _liveEpoch ||
          _selectedLiveSource != sourceIndex) {
        return;
      }
      setState(() {
        _liveLoading = false;
        _liveError = '读取直播列表失败：$e';
      });
    }
  }
  void _openVideoDetail(CmsSite site, CmsVideo video) {
    if (video.id.startsWith('netdisk_qr:')) {
      final driveKey = video.id.substring('netdisk_qr:'.length).trim();
      showNetdiskAccountSheet(
        context,
        widget.storage,
        initialDriveKey: driveKey.isEmpty ? null : driveKey,
        onChanged: () {
          if (mounted) setState(() {});
        },
      );
      return;
    }
    Navigator.of(context)
        .push(
          CupertinoPageRoute<void>(
            builder: (_) => DetailScreen(
              site: site,
              initialVideo: video,
              client: widget.client,
              storage: widget.storage,
            ),
          ),
        )
        .then((_) {
          if (mounted) setState(() {});
        });
  }
  void _showAppleNotice(String title, String message) {
    if (!mounted) return;
    showCupertinoDialog<void>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(title),
        content: Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(message),
        ),
        actions: [
          CupertinoDialogAction(
            isDefaultAction: true,
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('好'),
          ),
        ],
      ),
    );
  }
  Future<void> _quickImportFromClipboard() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = (data?.text ?? '').trim();
    if (text.isEmpty) {
      _showImportConfigDialog();
      return;
    }
    try {
      if (CmsParser.isBackupPayload(text)) {
        await widget.storage.importBackupJson(text);
        _reloadSavedConfig();
        _showAppleNotice('已从剪贴板恢复备份', '备份中的配置、收藏与观看历史已同步生效。');
        return;
      }
      final entries = await widget.client.resolveBatchSubscriptions(
        rawTextInput: text,
      );
      final merged = await widget.storage.upsertSubscriptions(entries);
      _reloadSavedConfig();
      final siteCount = merged?.publicSupportedSites.length ?? 0;
      final liveCount = merged?.publicLives.length ?? 0;
      _showAppleNotice(
        '订阅导入成功',
        '本次成功导入 ${entries.length} 个订阅源，当前共 $siteCount 个可用片源'
        '${liveCount > 0 ? "、$liveCount 个直播源" : ""}。',
      );
    } catch (e) {
      _showImportConfigDialog(initialInput: text, initialError: '剪贴板内容未能直接导入：$e');
    }
  }
  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final isLandscape = size.width > size.height;
    const dockItems = <(IconData, IconData, String)>[
      (CupertinoIcons.play_rectangle, CupertinoIcons.play_rectangle_fill, '主页'),
      (
        CupertinoIcons.dot_radiowaves_left_right,
        CupertinoIcons.dot_radiowaves_left_right,
        '直播',
      ),
      (
        CupertinoIcons.square_favorites_alt,
        CupertinoIcons.square_favorites_alt_fill,
        '片库',
      ),
      (CupertinoIcons.gear_alt, CupertinoIcons.gear_alt_fill, '设置'),
    ];
    final pages = [
      _buildHomeTab(context, useSidebar: isLandscape),
      _buildLiveTab(context, useSidebar: isLandscape),
      _buildFavoritesHistoryTab(context, useSidebar: isLandscape),
      _buildSettingsTab(context, useSidebar: isLandscape),
    ];
    final safeTabIndex = _tabIndex.clamp(0, pages.length - 1);
    final ambientPoster = safeTabIndex == 0 && _homeVideos.isNotEmpty
        ? _homeVideos.first.poster
        : '';
    return Scaffold(
      backgroundColor: AppleColors.background,
      extendBody: true,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // Minimal Apple background: true black, faint top lift, and a softly
          // blurred ambient artwork of the featured title on the home tab.
          RepaintBoundary(
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 600),
              child: AppleAmbientBackground(
                key: ValueKey<String>(ambientPoster),
                artwork: ambientPoster.isEmpty
                    ? null
                    : Opacity(
                        opacity: 0.55,
                        child: PosterImage(
                          url: ambientPoster,
                          placeholder: const SizedBox.shrink(),
                        ),
                      ),
              ),
            ),
          ),
          // Active page content (with right clearance in landscape for the right-side Dock)
          Padding(
            padding: EdgeInsets.only(
              right: isLandscape
                  ? AppleLiquidGlassDock.landscapeFootprint(context)
                  : 0,
            ),
            child: RepaintBoundary(child: pages[safeTabIndex]),
          ),
          // iPhone / iPad style Dock:
          // - Portrait: bottom floating rounded-rectangle dock
          // - Landscape: right-side vertical dock
          if (isLandscape)
            Positioned(
              top: 0,
              bottom: 0,
              right: 0,
              child: Center(
                child: AppleLiquidGlassDock(
                  selectedIndex: safeTabIndex,
                  onSelect: (i) => setState(() => _tabIndex = i),
                  isLandscape: true,
                  items: dockItems,
                ),
              ),
            )
          else
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: Center(
                child: AppleLiquidGlassDock(
                  selectedIndex: safeTabIndex,
                  onSelect: (i) => setState(() => _tabIndex = i),
                  isLandscape: false,
                  items: dockItems,
                ),
              ),
            ),
        ],
      ),
    );
  }
  Widget _buildHomeTab(BuildContext context, {required bool useSidebar}) {
    if (_config == null) {
      return _buildWelcomeOnboarding(context);
    }
    final depots = _config!.depots;
    final history = widget.storage.getWatchHistory();
    final horizontalPad = useSidebar ? 24.0 : 16.0;
    final activeFilterCount = _currentFilter.activeDimensionCount;
    return SafeArea(
      bottom: false,
      right: !useSidebar,
      child: CustomScrollView(
        physics: const BouncingScrollPhysics(
          parent: AlwaysScrollableScrollPhysics(),
        ),
        slivers: [
          CupertinoSliverRefreshControl(
            onRefresh: _loadSiteHome,
          ),
          // Horizontal padding is applied per block so the horizontal shelves
          // (继续观看 / 分类) can run edge-to-edge with a soft fade instead of
          // being hard-clipped by the page margin.
          SliverPadding(
            padding: EdgeInsets.fromLTRB(
              0,
              useSidebar && _isMacOS ? 24 : 12,
              0,
              12,
            ),
            sliver: SliverToBoxAdapter(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Top Header Bar (5x tap on brand logo opens password-protected private list;
                  // top-right button is Global Search)
                  Padding(
                    padding: EdgeInsets.symmetric(horizontal: horizontalPad),
                    child: Row(
                      children: [
                        Flexible(
                          flex: 0,
                          child: GestureDetector(
                            key: const ValueKey('home-brand-logo'),
                            behavior: HitTestBehavior.opaque,
                            onTap: _handleHomeLogoTap,
                            child: const MarcoBrandLogo(
                              iconSize: 28,
                              fontSize: 16.5,
                            ),
                          ),
                        ),
                        const Spacer(),
                        if (depots.isNotEmpty) ...[
                          _buildHeaderCapsule(
                            icon: CupertinoIcons.archivebox_fill,
                            label: widget.storage.activeDepotName.isEmpty
                                ? '切换仓库'
                                : widget.storage.activeDepotName,
                            onTap: _showDepotPickerDialog,
                          ),
                          const SizedBox(width: 6),
                        ],
                        _buildHeaderCapsule(
                          icon: _activeSite?.isPrivate == true
                              ? CupertinoIcons.lock_shield_fill
                              : CupertinoIcons.layers_alt_fill,
                          label: _activeSite?.name ?? '选择片源',
                          onTap: _showSitePickerDialog,
                        ),
                        const SizedBox(width: 6),
                        GestureDetector(
                          key: const ValueKey('home-netdisk-button'),
                          onTap: () => showNetdiskAccountSheet(
                            context,
                            widget.storage,
                            onChanged: () {
                              if (mounted) setState(() {});
                            },
                          ),
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(999),
                            child: BackdropFilter(
                              filter: ImageFilter.blur(sigmaX: 28, sigmaY: 28),
                              child: Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 10.5,
                                  vertical: 7,
                                ),
                                decoration: BoxDecoration(
                                  gradient: const LinearGradient(
                                    begin: Alignment.topCenter,
                                    end: Alignment.bottomCenter,
                                    colors: [
                                      Color(0xD42A2A32),
                                      Color(0xC21D1D24),
                                    ],
                                  ),
                                  borderRadius: BorderRadius.circular(999),
                                  border: Border.all(
                                    color: AppleColors.glassBorder,
                                    width: 0.7,
                                  ),
                                ),
                                child: const Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(
                                      CupertinoIcons.qrcode_viewfinder,
                                      color: AppleColors.systemBlue,
                                      size: 13.5,
                                    ),
                                    SizedBox(width: 4),
                                    Text(
                                      '网盘',
                                      style: TextStyle(
                                        color: AppleColors.labelPrimary,
                                        fontSize: 12.5,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 6),
                        CupertinoButton(
                          key: const ValueKey('home-search-button'),
                          padding: EdgeInsets.zero,
                          minSize: 33,
                          onPressed: _showGlobalSearchSheet,
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(999),
                            child: BackdropFilter(
                              filter: ImageFilter.blur(sigmaX: 28, sigmaY: 28),
                              child: Container(
                                width: 33,
                                height: 33,
                                decoration: BoxDecoration(
                                  gradient: const LinearGradient(
                                    begin: Alignment.topCenter,
                                    end: Alignment.bottomCenter,
                                    colors: [
                                      Color(0xD42A2A32),
                                      Color(0xC21D1D24),
                                    ],
                                  ),
                                  borderRadius: BorderRadius.circular(999),
                                  border: Border.all(
                                    color: AppleColors.glassBorder,
                                    width: 0.7,
                                  ),
                                ),
                                alignment: Alignment.center,
                                child: const Icon(
                                  CupertinoIcons.search,
                                  color: AppleColors.labelPrimary,
                                  size: 16,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  // If config has only Android JAR (`csp_*`) sites and no direct HTTP sites
                  if (_activeSite == null) ...[
                    const SizedBox(height: 24),
                    Padding(
                      padding: EdgeInsets.symmetric(horizontal: horizontalPad),
                      child: _buildPluginOnlyConfigNotice(context),
                    ),
                  ] else ...[
                    // Up Next ("继续观看") Shelf
                    if (history.isNotEmpty) ...[
                      const SizedBox(height: 20),
                      Padding(
                        padding:
                            EdgeInsets.symmetric(horizontal: horizontalPad),
                        child: Row(
                          children: [
                            const Text(
                              '继续观看',
                              style: TextStyle(
                                color: AppleColors.labelPrimary,
                                fontSize: 19,
                                fontWeight: FontWeight.w700,
                                letterSpacing: -0.4,
                              ),
                            ),
                            const Spacer(),
                            CupertinoButton(
                              padding: EdgeInsets.zero,
                              minSize: 24,
                              onPressed: () => setState(() {
                                _tabIndex = 2;
                                _librarySegment = 1;
                              }),
                              child: const Text(
                                '查看全部',
                                style: TextStyle(
                                  color: AppleColors.systemBlue,
                                  fontSize: 13,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 10),
                      AppleEdgeFade(
                        fadeWidth: horizontalPad + 6,
                        child: SizedBox(
                          height: 90,
                          child: ListView.separated(
                            scrollDirection: Axis.horizontal,
                            clipBehavior: Clip.none,
                            physics: const BouncingScrollPhysics(),
                            padding: EdgeInsets.symmetric(
                              horizontal: horizontalPad,
                              vertical: 2,
                            ),
                            itemCount: history.length.clamp(0, 8),
                            separatorBuilder: (_, __) =>
                                const SizedBox(width: 12),
                            itemBuilder: (ctx, i) =>
                                _buildContinueWatchingCard(history[i]),
                          ),
                        ),
                      ),
                    ],
                    // Featured Hero Showcase Card on first page when videos exist
                    if (CspBridge.isMyDriveSite(_activeSite!)) ...[
                      const SizedBox(height: 18),
                      Padding(
                        padding:
                            EdgeInsets.symmetric(horizontal: horizontalPad),
                        child: _buildMyDriveNetdiskBanner(_activeSite!),
                      ),
                    ] else if (_homeVideos.isNotEmpty &&
                        _primaryTab == 'recommend' &&
                        !_currentFilter.hasSubFilter &&
                        _currentPage == 1) ...[
                      const SizedBox(height: 18),
                      Padding(
                        padding:
                            EdgeInsets.symmetric(horizontal: horizontalPad),
                        child: _buildHeroShowcaseCard(
                          _activeSite!,
                          _homeVideos.first,
                          wide: useSidebar,
                        ),
                      ),
                    ],
                    // Fixed 5 Primary Category Tabs (推荐、电影、剧集、动漫、综艺) + Filter Button
                    const SizedBox(height: 18),
                    Padding(
                      padding: EdgeInsets.symmetric(horizontal: horizontalPad),
                      child: Row(
                        children: [
                          Expanded(
                            child: SizedBox(
                              height: 36,
                              child: ListView.separated(
                                scrollDirection: Axis.horizontal,
                                physics: const BouncingScrollPhysics(),
                                itemCount: CmsParser.primaryHomeTabs.length,
                                separatorBuilder: (_, __) =>
                                    const SizedBox(width: 8),
                                itemBuilder: (ctx, idx) {
                                  final (tabKey, tabLabel) =
                                      CmsParser.primaryHomeTabs[idx];
                                  return _buildCategoryPill(
                                    label: tabLabel,
                                    selected: _primaryTab == tabKey,
                                    onTap: () => _selectPrimaryTab(tabKey),
                                  );
                                },
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          GestureDetector(
                            key: const ValueKey('home-filter-button'),
                            onTap: () => setState(
                              () => _filterExpanded = !_filterExpanded,
                            ),
                            child: AnimatedContainer(
                              duration: const Duration(milliseconds: 220),
                              curve: Curves.easeOutCubic,
                              height: 36,
                              padding:
                                  const EdgeInsets.symmetric(horizontal: 13),
                              decoration: BoxDecoration(
                                gradient: (_filterExpanded ||
                                        activeFilterCount > 0)
                                    ? const LinearGradient(
                                        begin: Alignment.topCenter,
                                        end: Alignment.bottomCenter,
                                        colors: [
                                          Color(0xFF0A84FF),
                                          Color(0xFF0066CC),
                                        ],
                                      )
                                    : const LinearGradient(
                                        begin: Alignment.topCenter,
                                        end: Alignment.bottomCenter,
                                        colors: [
                                          Color(0xCC2A2A32),
                                          Color(0xB81D1D24),
                                        ],
                                      ),
                                borderRadius: BorderRadius.circular(999),
                                border: Border.all(
                                  color: (_filterExpanded ||
                                          activeFilterCount > 0)
                                      ? const Color(0x885AC8FA)
                                      : const Color(0x2EFFFFFF),
                                  width: 0.65,
                                ),
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(
                                    CupertinoIcons.slider_horizontal_3,
                                    size: 14,
                                    color: (_filterExpanded ||
                                            activeFilterCount > 0)
                                        ? Colors.white
                                        : AppleColors.labelPrimary,
                                  ),
                                  const SizedBox(width: 5),
                                  Text(
                                    activeFilterCount > 0
                                        ? '筛选 · $activeFilterCount'
                                        : '筛选',
                                    style: TextStyle(
                                      color: (_filterExpanded ||
                                              activeFilterCount > 0)
                                          ? Colors.white
                                          : AppleColors.labelPrimary,
                                      fontSize: 13,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    // Collapsible Multi-Dimension Filter Drawer (类别 / 国家地区 / 年份)
                    AnimatedSize(
                      duration: const Duration(milliseconds: 260),
                      curve: Curves.easeOutCubic,
                      child: _filterExpanded
                          ? Padding(
                              padding: EdgeInsets.fromLTRB(
                                horizontalPad,
                                12,
                                horizontalPad,
                                2,
                              ),
                              child: _buildHomeFilterDrawer(),
                            )
                          : const SizedBox.shrink(),
                    ),
                  ],
                ],
              ),
            ),
          ),
          if (_activeSite != null) ...[
            if (_homeError != null)
              SliverFillRemaining(
                hasScrollBody: false,
                child: Padding(
                  padding: const EdgeInsets.all(32),
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(
                          CupertinoIcons.exclamationmark_triangle_fill,
                          color: AppleColors.systemOrange,
                          size: 38,
                        ),
                        const SizedBox(height: 12),
                        Text(
                          _homeError!,
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                            color: AppleColors.labelSecondary,
                            fontSize: 14,
                          ),
                        ),
                        const SizedBox(height: 16),
                        Wrap(
                          spacing: 10,
                          children: [
                            AppleCapsuleButton(
                              label: '重试',
                              icon: CupertinoIcons.refresh,
                              primary: true,
                              compact: true,
                              onPressed: _loadSiteHome,
                            ),
                            AppleCapsuleButton(
                              label: '切换其他片源',
                              icon: CupertinoIcons.layers_alt,
                              compact: true,
                              onPressed: _showSitePickerDialog,
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              )
            else
              SliverPadding(
                padding: EdgeInsets.symmetric(
                  horizontal: horizontalPad,
                  vertical: 8,
                ),
                sliver: SliverGrid(
                  gridDelegate: SliverGridDelegateWithMaxCrossAxisExtent(
                    maxCrossAxisExtent: useSidebar ? 176 : 132,
                    mainAxisSpacing: 16,
                    crossAxisSpacing: 12,
                    childAspectRatio: 0.56,
                  ),
                  delegate: SliverChildBuilderDelegate(
                    (context, index) {
                      final video = _homeVideos[index];
                      return _buildVideoCard(_activeSite!, video);
                    },
                    childCount: _homeVideos.length,
                  ),
                ),
              ),
            SliverToBoxAdapter(
              child: Padding(
                padding: EdgeInsets.only(
                  top: 18,
                  bottom: useSidebar ? 32 : 128,
                ),
                child: Center(
                  child: _homeLoading
                      ? const CupertinoActivityIndicator(radius: 13)
                      : (_homeVideos.isEmpty && _homeError == null)
                          ? const Padding(
                              padding: EdgeInsets.symmetric(vertical: 40),
                              child: Text(
                                '当前筛选条件下暂无影片，试试调整筛选或切换分类',
                                style: TextStyle(
                                  color: AppleColors.labelSecondary,
                                  fontSize: 14,
                                ),
                              ),
                            )
                      : (_currentPage < _pageCount
                          ? AppleCapsuleButton(
                              label: '加载更多影片',
                              icon: CupertinoIcons.arrow_down_circle,
                              onPressed: () => _reloadFilteredListing(
                                page: _currentPage + 1,
                              ),
                            )
                          : const SizedBox.shrink()),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
  Widget _buildWelcomeOnboarding(BuildContext context) {
    return SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: AppleGlassSurface(
              padding: const EdgeInsets.all(28),
              borderRadius: BorderRadius.circular(26),
              color: const Color(0xCC16171D),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const MarcoBrandLogo(
                    iconSize: 78,
                    fontSize: 24,
                    stacked: true,
                  ),
                  const SizedBox(height: 16),
                  const Text(
                    '把今晚，留给好故事。',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: AppleColors.labelPrimary,
                      fontSize: 24,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -0.5,
                    ),
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    '专为 Apple 生态（iPhone · iPad · Mac）打造的高清播放器。支持单仓/多仓配置地址、JSON 文本及跨平台备份一键导入。',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: AppleColors.labelSecondary,
                      fontSize: 14,
                      height: 1.5,
                    ),
                  ),
                  const SizedBox(height: 24),
                  SizedBox(
                    width: double.infinity,
                    child: AppleCapsuleButton(
                      label: '导入配置地址或 JSON',
                      icon: CupertinoIcons.add_circled_solid,
                      primary: true,
                      onPressed: () => _showImportConfigDialog(),
                    ),
                  ),
                  const SizedBox(height: 10),
                  SizedBox(
                    width: double.infinity,
                    child: AppleCapsuleButton(
                      label: '一键粘贴剪贴板并导入',
                      icon: CupertinoIcons.doc_on_clipboard_fill,
                      onPressed: _quickImportFromClipboard,
                    ),
                  ),
                  const SizedBox(height: 10),
                  SizedBox(
                    width: double.infinity,
                    child: AppleCapsuleButton(
                      label: '从跨平台备份恢复',
                      icon: CupertinoIcons.arrow_2_circlepath,
                      onPressed: _showBackupRestoreDialog,
                    ),
                  ),
                  const SizedBox(height: 20),
                  const Text(
                    '本产品仅为播放工具，不内置、不提供、不托管任何影视资源或配置地址。',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: AppleColors.labelTertiary,
                      fontSize: 11,
                      height: 1.4,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
  Widget _buildPluginOnlyConfigNotice(BuildContext context) {
    final pluginCount = _config?.pluginOnlySites.length ?? 0;
    final depotCount = _config?.depots.length ?? 0;
    final liveCount = _config?.lives.length ?? 0;
    return AppleGlassSurface(
      padding: const EdgeInsets.all(22),
      borderRadius: BorderRadius.circular(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                CupertinoIcons.info_circle_fill,
                color: AppleColors.systemBlue,
                size: 22,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  '已成功加载配置（共 $pluginCount 个安卓专用插件站点）',
                  style: const TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          const Text(
            '当前仓库中的片源全部为安卓 JAR 爬虫插件（csp_*）。由于苹果 iOS / macOS 系统安全机制限制，无法直接运行安卓 DEX 字节码插件；Apple 端支持所有标准 HTTP/HTTPS CMS 站点（type 0 / 1 / 4）与 M3U/TXT 电视直播源。',
            style: TextStyle(
              color: AppleColors.labelSecondary,
              fontSize: 13,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              if (depotCount > 1)
                AppleCapsuleButton(
                  label: '切换其他子仓库 ($depotCount)',
                  icon: CupertinoIcons.archivebox_fill,
                  primary: true,
                  compact: true,
                  onPressed: _showDepotPickerDialog,
                ),
              if (liveCount > 0)
                AppleCapsuleButton(
                  label: '观看电视直播 ($liveCount)',
                  icon: CupertinoIcons.dot_radiowaves_left_right,
                  primary: depotCount <= 1,
                  compact: true,
                  onPressed: () => setState(() => _tabIndex = 1),
                ),
              AppleCapsuleButton(
                label: '查看全部站点列表',
                icon: CupertinoIcons.list_bullet,
                compact: true,
                onPressed: _showSitePickerDialog,
              ),
              AppleCapsuleButton(
                label: '导入其他配置',
                icon: CupertinoIcons.add,
                compact: true,
                onPressed: _showImportConfigDialog,
              ),
            ],
          ),
        ],
      ),
    );
  }
  Widget _buildHeaderCapsule({
    required IconData icon,
    required String label,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(999),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 28, sigmaY: 28),
          child: Container(
            constraints: const BoxConstraints(maxWidth: 146),
            padding: const EdgeInsets.symmetric(horizontal: 11.5, vertical: 7),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  Color(0xD42A2A32),
                  Color(0xC21D1D24),
                ],
              ),
              borderRadius: BorderRadius.circular(999),
              border: Border.all(
                color: AppleColors.glassBorder,
                width: 0.7,
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, color: AppleColors.labelSecondary, size: 13.5),
                const SizedBox(width: 5),
                Flexible(
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppleColors.labelPrimary,
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                const SizedBox(width: 4),
                const Icon(
                  CupertinoIcons.chevron_down,
                  color: AppleColors.labelSecondary,
                  size: 11.5,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
  Widget _buildHeroShowcaseCard(
    CmsSite site,
    CmsVideo featured, {
    required bool wide,
  }) {
    final tagChips = <String>[
      if (featured.year.trim().isNotEmpty) featured.year.trim(),
      if (featured.area.trim().isNotEmpty) featured.area.trim(),
      if (featured.remarks.trim().isNotEmpty) featured.remarks.trim(),
    ];
    final infoLine = featured.description.trim().isNotEmpty
        ? featured.description.trim()
        : (featured.actor.trim().isNotEmpty ? '主演：${featured.actor.trim()}' : '');
    return GestureDetector(
      onTap: () => _openVideoDetail(site, featured),
      child: Container(
        height: wide ? 256 : 210,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(24),
          color: const Color(0xFF111114),
          border: Border.all(color: AppleColors.glassBorder, width: 0.75),
          boxShadow: const [
            BoxShadow(
              color: Color(0x55000000),
              blurRadius: 24,
              offset: Offset(0, 10),
            ),
          ],
        ),
        clipBehavior: Clip.antiAlias,
        child: Stack(
          fit: StackFit.expand,
          children: [
            ImageFiltered(
              imageFilter: ImageFilter.blur(sigmaX: 36, sigmaY: 36),
              child: PosterImage(
                url: featured.poster,
                title: featured.title,
                placeholder: const SizedBox.shrink(),
              ),
            ),
            Container(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    Color(0x55000000),
                    Color(0xAD07080C),
                    Color(0xEB050508),
                  ],
                  stops: [0.0, 0.55, 1.0],
                ),
              ),
            ),
            Padding(
              padding: EdgeInsets.all(wide ? 22 : 16),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(14),
                    child: AspectRatio(
                      aspectRatio: 2 / 3,
                      child: PosterImage(
                        url: featured.poster,
                        title: featured.title,
                        placeholder: _buildPosterPlaceholder(),
                      ),
                    ),
                  ),
                  SizedBox(width: wide ? 20 : 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Top badge
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 8.5,
                            vertical: 3.5,
                          ),
                          decoration: BoxDecoration(
                            color: const Color(0x38FFFFFF),
                            borderRadius: BorderRadius.circular(999),
                            border: Border.all(
                              color: const Color(0x30FFFFFF),
                              width: 0.5,
                            ),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              const Icon(
                                CupertinoIcons.sparkles,
                                color: Color(0xFF5AC8FA),
                                size: 11,
                              ),
                              const SizedBox(width: 4),
                              Flexible(
                                child: Text(
                                  '今日首推 · ${site.name}',
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    color: AppleColors.labelPrimary,
                                    fontSize: 11,
                                    fontWeight: FontWeight.w600,
                                    letterSpacing: 0.1,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 8),
                        // Title
                        Text(
                          featured.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: AppleColors.labelPrimary,
                            fontSize: wide ? 23 : 18.5,
                            fontWeight: FontWeight.w700,
                            letterSpacing: -0.4,
                            height: 1.2,
                          ),
                        ),
                        if (tagChips.isNotEmpty) ...[
                          const SizedBox(height: 7),
                          Wrap(
                            spacing: 6,
                            runSpacing: 4,
                            children: [
                              for (final chip in tagChips)
                                Container(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 7,
                                    vertical: 2.5,
                                  ),
                                  decoration: BoxDecoration(
                                    color: const Color(0x29FFFFFF),
                                    borderRadius: BorderRadius.circular(6),
                                  ),
                                  child: Text(
                                    chip,
                                    style: const TextStyle(
                                      color: AppleColors.labelSecondary,
                                      fontSize: 11,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ],
                        if (infoLine.isNotEmpty) ...[
                          const SizedBox(height: 7),
                          Text(
                            infoLine,
                            maxLines: wide ? 2 : 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: AppleColors.labelSecondary,
                              fontSize: 12,
                              height: 1.35,
                            ),
                          ),
                        ],
                        const Spacer(),
                        // Bottom row: Watch Now button anchored at bottom-right corner
                        Row(
                          mainAxisAlignment: MainAxisAlignment.end,
                          children: [
                            AppleCapsuleButton(
                              key: const ValueKey('hero-watch-now-button'),
                              label: '立即观看',
                              icon: CupertinoIcons.play_fill,
                              primary: true,
                              compact: true,
                              onPressed: () => _openVideoDetail(site, featured),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
  Widget _buildHomeFilterDrawer() {
    final subCats = CmsParser.subCategoriesForPrimaryTab(
      _categories,
      _primaryTab,
    );
    final existingNames = subCats.map((c) => c.name.trim()).toSet();
    final extraGenres = [
      for (final g in CmsParser.fallbackGenreTags)
        if (!existingNames.any((n) => n.contains(g))) g,
    ];
    final hasAnyFilter = _currentFilter.hasSubFilter;

    return AppleGlassSurface(
      key: const ValueKey('home-filter-drawer'),
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      borderRadius: BorderRadius.circular(20),
      blurSigma: 32,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                CupertinoIcons.slider_horizontal_3,
                color: AppleColors.systemBlue,
                size: 14,
              ),
              const SizedBox(width: 6),
              const Text(
                '多维片库筛选',
                style: TextStyle(
                  color: AppleColors.labelPrimary,
                  fontSize: 12.5,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const Spacer(),
              if (hasAnyFilter)
                GestureDetector(
                  onTap: () {
                    setState(() {
                      _filterSubCategoryId = '';
                      _filterGenreLabel = '';
                      _filterArea = '';
                      _filterYear = '';
                    });
                    _reloadFilteredListing(page: 1);
                  },
                  child: const Text(
                    '重置筛选',
                    style: TextStyle(
                      color: AppleColors.systemBlue,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 10),
          // 1. 类别 (Sub-categories from site + Genre tags)
          _buildFilterDimensionRow(
            title: '类别',
            children: [
              _buildMiniFilterChip(
                label: '全部',
                selected:
                    _filterSubCategoryId.isEmpty && _filterGenreLabel.isEmpty,
                onTap: () {
                  setState(() {
                    _filterSubCategoryId = '';
                    _filterGenreLabel = '';
                  });
                  _reloadFilteredListing(page: 1);
                },
              ),
              for (final cat in subCats)
                _buildMiniFilterChip(
                  label: cat.name,
                  selected: _filterSubCategoryId == cat.id,
                  onTap: () {
                    setState(() {
                      _filterSubCategoryId = cat.id;
                      _filterGenreLabel = '';
                    });
                    _reloadFilteredListing(page: 1);
                  },
                ),
              for (final genre in extraGenres)
                _buildMiniFilterChip(
                  label: genre,
                  selected: _filterGenreLabel == genre &&
                      _filterSubCategoryId.isEmpty,
                  onTap: () {
                    setState(() {
                      _filterGenreLabel = genre;
                    });
                    _reloadFilteredListing(page: 1);
                  },
                ),
            ],
          ),
          const SizedBox(height: 8),
          // 2. 地区 (Area / Country)
          _buildFilterDimensionRow(
            title: '地区',
            children: [
              for (final area in CmsParser.filterAreas)
                _buildMiniFilterChip(
                  label: area,
                  selected: (_filterArea.isEmpty && area == '全部') ||
                      _filterArea == area,
                  onTap: () {
                    setState(() {
                      _filterArea = area == '全部' ? '' : area;
                    });
                    _reloadFilteredListing(page: 1);
                  },
                ),
            ],
          ),
          const SizedBox(height: 8),
          // 3. 年份 (Year)
          _buildFilterDimensionRow(
            title: '年份',
            children: [
              for (final yr in CmsParser.filterYears)
                _buildMiniFilterChip(
                  label: yr,
                  selected: (_filterYear.isEmpty && yr == '全部') ||
                      _filterYear == yr,
                  onTap: () {
                    setState(() {
                      _filterYear = yr == '全部' ? '' : yr;
                    });
                    _reloadFilteredListing(page: 1);
                  },
                ),
            ],
          ),
        ],
      ),
    );
  }
  Widget _buildFilterDimensionRow({
    required String title,
    required List<Widget> children,
  }) {
    return Row(
      children: [
        SizedBox(
          width: 34,
          child: Text(
            title,
            style: const TextStyle(
              color: AppleColors.labelSecondary,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        Expanded(
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            physics: const BouncingScrollPhysics(),
            child: Row(
              children: [
                for (int i = 0; i < children.length; i++) ...[
                  if (i > 0) const SizedBox(width: 6),
                  children[i],
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
  Widget _buildMiniFilterChip({
    required String label,
    required bool selected,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 5),
        decoration: BoxDecoration(
          color: selected
              ? AppleColors.systemBlue
              : const Color(0x24FFFFFF),
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
            color: selected
                ? const Color(0x885AC8FA)
                : const Color(0x22FFFFFF),
            width: 0.5,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: selected ? Colors.white : AppleColors.labelPrimary,
            fontSize: 12,
            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
          ),
        ),
      ),
    );
  }
  Widget _buildContinueWatchingCard(WatchHistoryEntry entry) {
    return GestureDetector(
      onTap: () {
        if (_config == null) return;
        final site = _config!.supportedSites.firstWhere(
          (s) => s.key == entry.site,
          orElse: () => _activeSite!,
        );
        _openVideoDetail(
          site,
          CmsVideo(
            id: entry.id,
            title: entry.title,
            poster: entry.poster,
          ),
        );
      },
      child: AppleGlassSurface(
        borderRadius: BorderRadius.circular(18),
        padding: const EdgeInsets.all(10),
        blurSigma: 32,
        child: SizedBox(
          width: 214,
          child: Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: SizedBox(
                  width: 48,
                  height: 66,
                  child: PosterImage(
                    url: entry.poster,
                    title: entry.title,
                    placeholder: _buildPosterPlaceholder(),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      entry.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppleColors.labelPrimary,
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      entry.episode.isEmpty ? '继续观看' : entry.episode,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppleColors.labelSecondary,
                        fontSize: 12,
                      ),
                    ),
                    const SizedBox(height: 8),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(2),
                      child: LinearProgressIndicator(
                        value: entry.progressFraction > 0
                            ? entry.progressFraction
                            : 0.15,
                        minHeight: 3,
                        backgroundColor: AppleColors.groupedTertiary,
                        valueColor: const AlwaysStoppedAnimation<Color>(
                          AppleColors.systemBlue,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
  Widget _buildCategoryPill({
    required String label,
    required bool selected,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
        padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 7),
        decoration: BoxDecoration(
          gradient: selected
              ? const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color(0xFFFFFFFF),
                    Color(0xFFEAEAF0),
                  ],
                )
              : const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color(0xCC2A2A32),
                    Color(0xB81D1D24),
                  ],
                ),
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
            color: selected
                ? const Color(0xFFFFFFFF)
                : const Color(0x2EFFFFFF),
            width: 0.65,
          ),
        ),
        alignment: Alignment.center,
        child: Text(
          label,
          style: TextStyle(
            color: selected ? const Color(0xFF000000) : AppleColors.labelPrimary,
            fontSize: 13,
            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
          ),
        ),
      ),
    );
  }
  Widget _buildMyDriveNetdiskBanner(CmsSite site) {
    return Container(
      key: const ValueKey('mydrive-netdisk-banner'),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF12233B), Color(0xFF14161E)],
        ),
        borderRadius: BorderRadius.circular(22),
        border: Border.all(
          color: AppleColors.systemBlue.withValues(alpha: 0.45),
          width: 0.8,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                CupertinoIcons.qrcode_viewfinder,
                color: Color(0xFF5AC8FA),
                size: 22,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  '${site.name} · 电视端网盘扫码与账号管理',
                  style: const TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              AppleCapsuleButton(
                label: '打开网盘中心',
                icon: CupertinoIcons.qrcode,
                primary: true,
                compact: true,
                onPressed: () => showNetdiskAccountSheet(
                  context,
                  widget.storage,
                  onChanged: () {
                    if (mounted) setState(() {});
                  },
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          const Text(
            '点击下方网盘即可弹出官方扫码窗口，支持夸克（我的夸父）、UC（我的优汐）、百度（我的嘟嘟）、阿里云盘（我的阿狸）4K 原画线路：',
            style: TextStyle(
              color: AppleColors.labelSecondary,
              fontSize: 12.5,
              height: 1.4,
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final drive in NetdiskService.drives)
                AppleCapsuleButton(
                  label:
                      '${drive.tvAlias} · ${widget.storage.isNetdiskLoggedIn(drive.key) ? "已启用" : "扫码登录"}',
                  icon: widget.storage.isNetdiskLoggedIn(drive.key)
                      ? CupertinoIcons.checkmark_seal_fill
                      : CupertinoIcons.qrcode_viewfinder,
                  primary: widget.storage.isNetdiskLoggedIn(drive.key),
                  compact: true,
                  onPressed: () => showNetdiskAccountSheet(
                    context,
                    widget.storage,
                    initialDriveKey: drive.key,
                    onChanged: () {
                      if (mounted) setState(() {});
                    },
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
  Widget _buildPosterPlaceholder() {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFF242428), Color(0xFF161618)],
        ),
      ),
      alignment: Alignment.center,
      child: const Icon(
        CupertinoIcons.film,
        color: AppleColors.labelTertiary,
        size: 26,
      ),
    );
  }
  Widget _buildNetdiskCardPoster(String driveKey, String title) {
    final loggedIn = widget.storage.isNetdiskLoggedIn(driveKey);
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: loggedIn
              ? const [Color(0xFF143527), Color(0xFF0E1D17)]
              : const [Color(0xFF152946), Color(0xFF101724)],
        ),
      ),
      alignment: Alignment.center,
      padding: const EdgeInsets.all(10),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            loggedIn ? CupertinoIcons.cloud_fill : CupertinoIcons.qrcode_viewfinder,
            color: loggedIn ? const Color(0xFF30D158) : const Color(0xFF5AC8FA),
            size: 38,
          ),
          const SizedBox(height: 8),
          Text(
            title.split('·').first.trim(),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 13,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 4),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.35),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(
              loggedIn ? '已登录+启用中' : '点击扫码登录',
              style: TextStyle(
                color: loggedIn ? const Color(0xFF30D158) : const Color(0xFF5AC8FA),
                fontSize: 10,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }
  Widget _buildVideoCard(CmsSite site, CmsVideo video, {String? badge}) {
    final isNetdiskQr = video.id.startsWith('netdisk_qr:');
    final qrDriveKey =
        isNetdiskQr ? video.id.substring('netdisk_qr:'.length).trim() : '';
    return GestureDetector(
      onTap: () => _openVideoDetail(site, video),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                color: const Color(0xFF1C1C1E),
                border: Border.all(
                  color: AppleColors.glassBorder,
                  width: 0.5,
                ),
              ),
              clipBehavior: Clip.antiAlias,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  isNetdiskQr
                      ? _buildNetdiskCardPoster(qrDriveKey, video.title)
                      : PosterImage(
                          url: video.poster,
                          title: video.title,
                          placeholder: _buildPosterPlaceholder(),
                        ),
                  if (badge != null && badge.isNotEmpty)
                    Positioned(
                      top: 6,
                      left: 6,
                      right: 6,
                      child: Align(
                        alignment: Alignment.centerLeft,
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 7,
                            vertical: 2.5,
                          ),
                          decoration: BoxDecoration(
                            color: const Color(0xB3000000),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            badge,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: AppleColors.labelPrimary,
                              fontSize: 10,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ),
                    ),
                  if (video.remarks.isNotEmpty)
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 0,
                      child: Container(
                        padding: const EdgeInsets.fromLTRB(7, 14, 7, 5),
                        decoration: const BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [Color(0x00000000), Color(0xB3000000)],
                          ),
                        ),
                        child: Text(
                          video.remarks,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          textAlign: TextAlign.right,
                          style: const TextStyle(
                            color: AppleColors.labelPrimary,
                            fontSize: 10,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 7),
          Text(
            video.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: AppleColors.labelPrimary,
              fontSize: 13,
              fontWeight: FontWeight.w600,
              letterSpacing: -0.2,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            video.remarks.isNotEmpty ? video.remarks : site.name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: AppleColors.labelSecondary,
              fontSize: 11,
            ),
          ),
        ],
      ),
    );
  }
  void _showGlobalSearchSheet() {
    showAppleModalSheet<void>(
      context: context,
      title: '全站搜索',
      maxWidth: 680,
      trailingAction: CupertinoButton(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        minSize: 28,
        onPressed: () {
          if (widget.storage.getSearchHistory().isNotEmpty) {
            widget.storage.clearSearchHistory().then((_) {
              if (mounted) setState(() {});
              _searchSheetListener?.call();
            });
          }
        },
        child: const Text(
          '清空历史',
          style: TextStyle(color: AppleColors.labelSecondary, fontSize: 13),
        ),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setSheetState) {
            _searchSheetListener = () {
              if (ctx.mounted) setSheetState(() {});
            };
            final history = widget.storage.getSearchHistory();
            final size = MediaQuery.sizeOf(ctx);
            final wide = size.width > 640;
            return SizedBox(
              height: (size.height * 0.78).clamp(400.0, 680.0),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: CupertinoSearchTextField(
                            controller: _searchController,
                            autofocus: true,
                            placeholder: '搜索片名、演员或导演…',
                            backgroundColor: AppleColors.groupedSecondary,
                            style: const TextStyle(
                              color: AppleColors.labelPrimary,
                              fontSize: 15,
                            ),
                            placeholderStyle: const TextStyle(
                              color: AppleColors.labelSecondary,
                              fontSize: 15,
                            ),
                            onSubmitted: (kw) {
                              _runGlobalSearch(kw);
                              setSheetState(() {});
                            },
                          ),
                        ),
                        const SizedBox(width: 10),
                        AppleCapsuleButton(
                          label: '搜索',
                          primary: true,
                          compact: true,
                          onPressed: () {
                            _runGlobalSearch(_searchController.text);
                            setSheetState(() {});
                          },
                        ),
                      ],
                    ),
                    if (history.isNotEmpty) ...[
                      const SizedBox(height: 10),
                      SizedBox(
                        height: 30,
                        child: ListView.separated(
                          scrollDirection: Axis.horizontal,
                          itemCount: history.length,
                          separatorBuilder: (_, __) => const SizedBox(width: 8),
                          itemBuilder: (ctx, i) {
                            final kw = history[i];
                            return GestureDetector(
                              onTap: () {
                                _searchController.text = kw;
                                _runGlobalSearch(kw);
                                setSheetState(() {});
                              },
                              child: Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 5,
                                ),
                                decoration: BoxDecoration(
                                  color: AppleColors.groupedSecondary,
                                  borderRadius: BorderRadius.circular(999),
                                  border: Border.all(
                                    color: AppleColors.glassBorder,
                                    width: 0.5,
                                  ),
                                ),
                                child: Text(
                                  kw,
                                  style: const TextStyle(
                                    color: AppleColors.labelSecondary,
                                    fontSize: 12,
                                  ),
                                ),
                              ),
                            );
                          },
                        ),
                      ),
                    ],
                    const SizedBox(height: 12),
                    if (_searching)
                      const Padding(
                        padding: EdgeInsets.only(bottom: 10),
                        child: Row(
                          children: [
                            CupertinoActivityIndicator(radius: 9),
                            SizedBox(width: 8),
                            Text(
                              '正在并发检索所有可用片源…',
                              style: TextStyle(
                                color: AppleColors.labelSecondary,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                    Expanded(
                      child: _searchResults.isEmpty && !_searching
                          ? const Center(
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(
                                    CupertinoIcons.search,
                                    color: AppleColors.labelTertiary,
                                    size: 42,
                                  ),
                                  SizedBox(height: 10),
                                  Text(
                                    '输入关键词，并发检索全部已启用片源',
                                    style: TextStyle(
                                      color: AppleColors.labelSecondary,
                                      fontSize: 14,
                                    ),
                                  ),
                                ],
                              ),
                            )
                          : GridView.builder(
                              padding: const EdgeInsets.only(bottom: 16),
                              gridDelegate:
                                  SliverGridDelegateWithMaxCrossAxisExtent(
                                maxCrossAxisExtent: wide ? 160 : 124,
                                mainAxisSpacing: 14,
                                crossAxisSpacing: 12,
                                childAspectRatio: 0.56,
                              ),
                              itemCount: _searchResults.length,
                              itemBuilder: (ctx, i) {
                                final (site, video) = _searchResults[i];
                                return GestureDetector(
                                  onTap: () {
                                    Navigator.of(ctx).pop();
                                    _openVideoDetail(site, video);
                                  },
                                  child: IgnorePointer(
                                    child: _buildVideoCard(
                                      site,
                                      video,
                                      badge: site.name,
                                    ),
                                  ),
                                );
                              },
                            ),
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    ).whenComplete(() {
      _searchSheetListener = null;
    });
  }

  void _handleHomeLogoTap() {
    final now = DateTime.now();
    if (_lastLogoTapAt == null ||
        now.difference(_lastLogoTapAt!) > const Duration(milliseconds: 2500)) {
      _logoTapCount = 1;
    } else {
      _logoTapCount++;
    }
    _lastLogoTapAt = now;
    if (_logoTapCount >= 5) {
      _logoTapCount = 0;
      _lastLogoTapAt = null;
      _openPrivateSpaceWithAuth();
    }
  }

  void _openPrivateSpaceWithAuth() {
    if (!widget.storage.hasPrivacyPassword) {
      _showSetPrivacyPasswordDialog(
        title: '设置隐私资源密码',
        subtitle: '首次开启隐藏空间，请设置访问密码（后续连点首页 Logo 5 次即可输入密码进入隐藏列表）。',
        onSuccess: () {
          setState(() => _privateSessionUnlocked = true);
          _showPrivateResourcesSheet();
        },
      );
      return;
    }
    if (_privateSessionUnlocked) {
      _showPrivateResourcesSheet();
      return;
    }
    _showVerifyPrivacyPasswordDialog(
      onSuccess: () {
        setState(() => _privateSessionUnlocked = true);
        _showPrivateResourcesSheet();
      },
    );
  }

  void _showSetPrivacyPasswordDialog({
    String title = '设置隐私密码',
    String subtitle = '设置密码后，连点首页左上角 Logo 5 次即可验证进入隐私资源隐藏列表。',
    VoidCallback? onSuccess,
  }) {
    final pwdCtrl = TextEditingController();
    final confirmCtrl = TextEditingController();
    String? err;
    showCupertinoDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => CupertinoAlertDialog(
          title: Text(title),
          content: Padding(
            padding: const EdgeInsets.only(top: 10),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  subtitle,
                  style: const TextStyle(
                    fontSize: 12.5,
                    color: AppleColors.labelSecondary,
                  ),
                ),
                const SizedBox(height: 12),
                CupertinoTextField(
                  controller: pwdCtrl,
                  obscureText: true,
                  placeholder: '输入新密码（至少 4 位）',
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                  style: const TextStyle(color: AppleColors.labelPrimary),
                ),
                const SizedBox(height: 8),
                CupertinoTextField(
                  controller: confirmCtrl,
                  obscureText: true,
                  placeholder: '再次确认密码',
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                  style: const TextStyle(color: AppleColors.labelPrimary),
                ),
                if (err != null) ...[
                  const SizedBox(height: 8),
                  Text(
                    err!,
                    style: const TextStyle(
                      color: AppleColors.systemRed,
                      fontSize: 12,
                    ),
                  ),
                ],
              ],
            ),
          ),
          actions: [
            CupertinoDialogAction(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('取消'),
            ),
            CupertinoDialogAction(
              isDefaultAction: true,
              onPressed: () async {
                final p1 = pwdCtrl.text.trim();
                final p2 = confirmCtrl.text.trim();
                if (p1.length < 4) {
                  setDialogState(() => err = '密码长度至少为 4 位');
                  return;
                }
                if (p1 != p2) {
                  setDialogState(() => err = '两次输入的密码不一致');
                  return;
                }
                await widget.storage.setPrivacyPassword(p1);
                if (ctx.mounted) Navigator.of(ctx).pop();
                onSuccess?.call();
              },
              child: const Text('保存并进入'),
            ),
          ],
        ),
      ),
    );
  }

  void _showVerifyPrivacyPasswordDialog({required VoidCallback onSuccess}) {
    final pwdCtrl = TextEditingController();
    String? err;
    showCupertinoDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => CupertinoAlertDialog(
          title: const Text('解锁隐私资源列表'),
          content: Padding(
            padding: const EdgeInsets.only(top: 10),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text(
                  '请输入隐私访问密码以查看隐藏订阅与隐私片源：',
                  style: TextStyle(
                    fontSize: 12.5,
                    color: AppleColors.labelSecondary,
                  ),
                ),
                const SizedBox(height: 12),
                CupertinoTextField(
                  controller: pwdCtrl,
                  obscureText: true,
                  autofocus: true,
                  placeholder: '请输入隐私密码',
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                  style: const TextStyle(color: AppleColors.labelPrimary),
                  onSubmitted: (_) {
                    if (widget.storage.verifyPrivacyPassword(pwdCtrl.text)) {
                      Navigator.of(ctx).pop();
                      onSuccess();
                    } else {
                      setDialogState(() => err = '密码错误，请重试');
                    }
                  },
                ),
                if (err != null) ...[
                  const SizedBox(height: 8),
                  Text(
                    err!,
                    style: const TextStyle(
                      color: AppleColors.systemRed,
                      fontSize: 12,
                    ),
                  ),
                ],
              ],
            ),
          ),
          actions: [
            CupertinoDialogAction(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('取消'),
            ),
            CupertinoDialogAction(
              isDefaultAction: true,
              onPressed: () {
                if (widget.storage.verifyPrivacyPassword(pwdCtrl.text)) {
                  Navigator.of(ctx).pop();
                  onSuccess();
                } else {
                  setDialogState(() => err = '密码错误，请重试');
                }
              },
              child: const Text('解锁'),
            ),
          ],
        ),
      ),
    );
  }

  void _showPrivateResourcesSheet() {
    showAppleModalSheet<void>(
      context: context,
      title: '隐私资源 · 隐藏列表',
      trailingAction: CupertinoButton(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        minSize: 28,
        onPressed: () {
          Navigator.of(context).pop();
          setState(() {
            _privateSessionUnlocked = false;
          });
          _reloadSavedConfig();
        },
        child: const Text(
          '锁定退出',
          style: TextStyle(color: AppleColors.systemOrange, fontSize: 13),
        ),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) {
          final allSubs = widget.storage.getSubscriptions();
          final privateSubs =
              allSubs.where((s) => s.isEffectivelyPrivate).toList();
          final privateSites = _config?.privateSupportedSites ?? const [];
          final privateLives = _config?.privateLives ?? const [];
          return ListView(
            shrinkWrap: true,
            padding: const EdgeInsets.all(16),
            children: [
              Wrap(
                spacing: 10,
                runSpacing: 10,
                children: [
                  AppleCapsuleButton(
                    label: '导入隐私订阅 (VOD/M3U/JSON)',
                    icon: CupertinoIcons.lock_fill,
                    primary: true,
                    compact: true,
                    onPressed: () {
                      Navigator.of(ctx).pop();
                      _showImportConfigDialog(
                        initialIsPrivate: true,
                        initialGroup: '隐私专区',
                      );
                    },
                  ),
                  AppleCapsuleButton(
                    label: '修改隐私密码',
                    icon: CupertinoIcons.lock_shield_fill,
                    compact: true,
                    onPressed: () {
                      _showSetPrivacyPasswordDialog(
                        title: '修改隐私访问密码',
                        subtitle: '请输入新的隐私空间密码：',
                      );
                    },
                  ),
                ],
              ),
              const SizedBox(height: 16),
              if (privateSites.isEmpty &&
                  privateSubs.isEmpty &&
                  privateLives.isEmpty)
                Container(
                  padding: const EdgeInsets.all(24),
                  decoration: BoxDecoration(
                    color: AppleColors.groupedSecondary,
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: const Column(
                    children: [
                      Icon(
                        CupertinoIcons.eye_slash_fill,
                        color: AppleColors.labelTertiary,
                        size: 38,
                      ),
                      SizedBox(height: 10),
                      Text(
                        '隐藏列表暂无隐私资源',
                        style: TextStyle(
                          color: AppleColors.labelPrimary,
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      SizedBox(height: 6),
                      Text(
                        '点击上方「导入隐私订阅」，或将下方现有订阅设为「隐私」，即可在此安全访问；日常浏览时完全隐藏。',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: AppleColors.labelSecondary,
                          fontSize: 12.5,
                          height: 1.4,
                        ),
                      ),
                    ],
                  ),
                ),
              if (privateSites.isNotEmpty) ...[
                AppleGroupedSection(
                  header: '隐藏影视片源 (${privateSites.length})',
                  footer: '点击任意隐私片源即可在主页立即加载浏览；点击右上角「锁定退出」后自动恢复隐藏。',
                  children: privateSites.map((s) {
                    final selected = s.key == _activeSite?.key;
                    return AppleGroupedTile(
                      icon: CupertinoIcons.lock_shield_fill,
                      iconBgColor: selected
                          ? AppleColors.systemBlue
                          : AppleColors.systemPurple,
                      title: s.name,
                      subtitle: '${s.group} · ${s.api}',
                      trailing: selected
                          ? const Icon(
                              CupertinoIcons.checkmark_alt,
                              color: AppleColors.systemBlue,
                              size: 18,
                            )
                          : const Text(
                              '立即访问',
                              style: TextStyle(
                                color: AppleColors.systemBlue,
                                fontSize: 12,
                              ),
                            ),
                      onTap: () {
                        Navigator.of(ctx).pop();
                        setState(() {
                          _privateSessionUnlocked = true;
                          _activeSite = s;
                        });
                        _loadSiteHome();
                      },
                    );
                  }).toList(),
                ),
                const SizedBox(height: 16),
              ],
              if (privateLives.isNotEmpty) ...[
                AppleGroupedSection(
                  header: '隐藏直播源 (${privateLives.length})',
                  children: [
                    for (int i = 0; i < privateLives.length; i++)
                      AppleGroupedTile(
                        icon: CupertinoIcons.dot_radiowaves_left_right,
                        iconBgColor: AppleColors.systemIndigo,
                        title: privateLives[i].name,
                        subtitle: '${privateLives[i].group} · M3U/TXT 直播',
                        trailingText: '去观看',
                        onTap: () {
                          Navigator.of(ctx).pop();
                          setState(() {
                            _privateSessionUnlocked = true;
                            _tabIndex = 1;
                          });
                          final idx = _visibleLives.indexWhere(
                            (l) => l.url == privateLives[i].url,
                          );
                          if (idx >= 0) _loadLivePlaylist(idx);
                        },
                      ),
                  ],
                ),
                const SizedBox(height: 16),
              ],
              if (allSubs.isNotEmpty)
                AppleGroupedSection(
                  header: '管理订阅隐私开关 (${allSubs.length})',
                  footer: '开启开关可将对应订阅移入隐私隐藏列表。',
                  children: allSubs.map((sub) {
                    final priv = sub.isEffectivelyPrivate;
                    return AppleGroupedTile(
                      icon: priv
                          ? CupertinoIcons.eye_slash_fill
                          : CupertinoIcons.globe,
                      iconBgColor: priv
                          ? AppleColors.systemPurple
                          : AppleColors.systemBlue,
                      title: '${sub.name} [${sub.format}]',
                      subtitle:
                          '分组：${sub.group} · ${sub.siteCount} 片源 / ${sub.liveCount} 直播',
                      trailing: CupertinoSwitch(
                        value: priv,
                        activeTrackColor: AppleColors.systemPurple,
                        onChanged: (val) async {
                          await widget.storage.toggleSubscriptionPrivacy(
                            sub.id,
                            val,
                          );
                          if (!val &&
                              (sub.group.contains('隐私') ||
                                  sub.group.contains('私密'))) {
                            await widget.storage.updateSubscriptionGroup(
                              sub.id,
                              '默认分组',
                            );
                          }
                          _reloadSavedConfig();
                          setSheetState(() {});
                        },
                      ),
                    );
                  }).toList(),
                ),
            ],
          );
        },
      ),
    );
  }

  Widget _buildLiveTab(BuildContext context, {required bool useSidebar}) {
    final lives = _visibleLives;
    final pad = useSidebar ? 28.0 : 16.0;
    if (lives.isEmpty) {
      return SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(
                  CupertinoIcons.dot_radiowaves_left_right,
                  color: AppleColors.labelTertiary,
                  size: 48,
                ),
                const SizedBox(height: 12),
                const Text(
                  '当前配置未包含 M3U / TXT 直播源',
                  style: TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 6),
                const Text(
                  '可直接导入 M3U / TXT 直播订阅地址或包含 lives 节点的 JSON 配置。',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: AppleColors.labelSecondary,
                    fontSize: 13,
                  ),
                ),
                const SizedBox(height: 18),
                AppleCapsuleButton(
                  label: '导入 M3U / 直播订阅',
                  icon: CupertinoIcons.add,
                  primary: true,
                  compact: true,
                  onPressed: () =>
                      _showImportConfigDialog(initialGroup: '电视直播'),
                ),
              ],
            ),
          ),
        ),
      );
    }
    return SafeArea(
      bottom: useSidebar,
      child: Padding(
        padding: EdgeInsets.fromLTRB(
          pad,
          useSidebar && _isMacOS ? 28 : 14,
          pad,
          0,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '电视直播',
              style: TextStyle(
                color: AppleColors.labelPrimary,
                fontSize: 28,
                fontWeight: FontWeight.w700,
                letterSpacing: -0.6,
              ),
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 36,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: lives.length,
                separatorBuilder: (_, __) => const SizedBox(width: 8),
                itemBuilder: (ctx, i) => _buildCategoryPill(
                  label: lives[i].name,
                  selected: i == _selectedLiveSource,
                  onTap: () => _loadLivePlaylist(i),
                ),
              ),
            ),
            const SizedBox(height: 14),
            if (_liveLoading)
              const Expanded(
                child: Center(child: CupertinoActivityIndicator(radius: 14)),
              )
            else if (_liveError != null)
              Expanded(
                child: Center(
                  child: Text(
                    _liveError!,
                    style: const TextStyle(color: AppleColors.systemRed),
                  ),
                ),
              )
            else
              Expanded(
                child: ListView.separated(
                  padding: EdgeInsets.only(bottom: useSidebar ? 24 : 124),
                  itemCount: _liveGroups.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 18),
                  itemBuilder: (ctx, gIdx) {
                    final group = _liveGroups[gIdx];
                    return AppleGroupedSection(
                      header: '${group.name} (${group.channels.length})',
                      children: group.channels.map((ch) {
                        return AppleGroupedTile(
                          icon: CupertinoIcons.tv_fill,
                          iconBgColor: AppleColors.systemIndigo,
                          title: ch.name,
                          trailingText: '${ch.urls.length} 线路',
                          onTap: () {
                            final safeIdx = _selectedLiveSource.clamp(
                              0,
                              lives.length - 1,
                            );
                            final liveSite = CmsSite(
                              key: 'live',
                              name: lives[safeIdx].name,
                              type: 1,
                              api: '',
                              headers: lives[safeIdx].headers,
                            );
                            final liveVideo = CmsVideo(
                              id: ch.name,
                              title: ch.name,
                              lines: [
                                CmsLine(
                                  name: group.name,
                                  episodes: List.generate(
                                    ch.urls.length,
                                    (idx) => CmsEpisode(
                                      name: '线路 ${idx + 1}',
                                      url: ch.urls[idx],
                                    ),
                                  ),
                                ),
                              ],
                            );
                            Navigator.of(context).push(
                              CupertinoPageRoute<void>(
                                builder: (_) => PlayerScreen(
                                  site: liveSite,
                                  video: liveVideo,
                                  initialLineIndex: 0,
                                  initialEpisodeIndex: 0,
                                  storage: widget.storage,
                                  isLive: true,
                                ),
                              ),
                            );
                          },
                        );
                      }).toList(),
                    );
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }
  Widget _buildFavoritesHistoryTab(
    BuildContext context, {
    required bool useSidebar,
  }) {
    final favorites = widget.storage.getFavorites();
    final history = widget.storage.getWatchHistory();
    final pad = useSidebar ? 28.0 : 16.0;
    return SafeArea(
      bottom: useSidebar,
      child: Padding(
        padding: EdgeInsets.fromLTRB(
          pad,
          useSidebar && _isMacOS ? 28 : 14,
          pad,
          0,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Text(
                  '我的片库',
                  style: TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 28,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.6,
                  ),
                ),
                const Spacer(),
                if (_librarySegment == 2 &&
                    DownloadService.instance.canOpenFolder &&
                    DownloadService.instance.rootPath.isNotEmpty)
                  CupertinoButton(
                    padding: EdgeInsets.zero,
                    minSize: 28,
                    onPressed: DownloadService.instance.openFolder,
                    child: const Text(
                      '打开文件夹',
                      style: TextStyle(
                        color: AppleColors.systemBlue,
                        fontSize: 13,
                      ),
                    ),
                  ),
                if (_librarySegment == 1 && history.isNotEmpty)
                  CupertinoButton(
                    padding: EdgeInsets.zero,
                    minSize: 28,
                    onPressed: () async {
                      await widget.storage.clearWatchHistory();
                      setState(() {});
                    },
                    child: const Text(
                      '清空记录',
                      style: TextStyle(
                        color: AppleColors.systemRed,
                        fontSize: 13,
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: CupertinoSlidingSegmentedControl<int>(
                backgroundColor: AppleColors.fill,
                thumbColor: const Color(0xFF636366),
                groupValue: _librarySegment,
                children: const {
                  0: Padding(
                    padding: EdgeInsets.symmetric(vertical: 7),
                    child: Text(
                      '收藏片单',
                      style: TextStyle(
                        color: AppleColors.labelPrimary,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  1: Padding(
                    padding: EdgeInsets.symmetric(vertical: 7),
                    child: Text(
                      '观看历史',
                      style: TextStyle(
                        color: AppleColors.labelPrimary,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  2: Padding(
                    padding: EdgeInsets.symmetric(vertical: 7),
                    child: Text(
                      '离线下载',
                      style: TextStyle(
                        color: AppleColors.labelPrimary,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                },
                onValueChanged: (v) {
                  if (v != null) setState(() => _librarySegment = v);
                },
              ),
            ),
            const SizedBox(height: 16),
            if (_librarySegment == 2)
              Expanded(child: _buildDownloadsList(useSidebar: useSidebar))
            else
            Expanded(
              child: _librarySegment == 0
                  ? (favorites.isEmpty
                      ? const Center(
                          child: Text(
                            '还没有收藏任何影片',
                            style: TextStyle(
                              color: AppleColors.labelSecondary,
                              fontSize: 14,
                            ),
                          ),
                        )
                      : GridView.builder(
                          padding: EdgeInsets.only(
                            bottom: useSidebar ? 24 : 124,
                          ),
                          gridDelegate:
                              SliverGridDelegateWithMaxCrossAxisExtent(
                            maxCrossAxisExtent: useSidebar ? 176 : 132,
                            mainAxisSpacing: 16,
                            crossAxisSpacing: 12,
                            childAspectRatio: 0.56,
                          ),
                          itemCount: favorites.length,
                          itemBuilder: (ctx, i) {
                            final fav = favorites[i];
                            final site = _config?.supportedSites.firstWhere(
                                  (s) => s.key == fav.site,
                                  orElse: () =>
                                      _activeSite ??
                                      CmsSite(
                                        key: fav.site,
                                        name: fav.site,
                                        type: 1,
                                        api: '',
                                      ),
                                ) ??
                                CmsSite(
                                  key: fav.site,
                                  name: fav.site,
                                  type: 1,
                                  api: '',
                                );
                            return _buildVideoCard(
                              site,
                              CmsVideo(
                                id: fav.id,
                                title: fav.title,
                                poster: fav.poster,
                                remarks: fav.remarks,
                              ),
                            );
                          },
                        ))
                  : (history.isEmpty
                      ? const Center(
                          child: Text(
                            '暂无观看历史',
                            style: TextStyle(
                              color: AppleColors.labelSecondary,
                              fontSize: 14,
                            ),
                          ),
                        )
                      : ListView(
                          padding: EdgeInsets.only(
                            bottom: useSidebar ? 24 : 124,
                          ),
                          children: [
                            AppleGroupedSection(
                              children: history.map((h) {
                                return AppleGroupedTile(
                                  icon: CupertinoIcons.play_circle_fill,
                                  iconBgColor: AppleColors.systemBlue,
                                  title: h.title,
                                  subtitle: '${h.episode} · ${h.line}',
                                  onTap: () {
                                    if (_config == null) return;
                                    final site =
                                        _config!.supportedSites.firstWhere(
                                      (s) => s.key == h.site,
                                      orElse: () => _activeSite!,
                                    );
                                    _openVideoDetail(
                                      site,
                                      CmsVideo(
                                        id: h.id,
                                        title: h.title,
                                        poster: h.poster,
                                      ),
                                    );
                                  },
                                );
                              }).toList(),
                            ),
                          ],
                        )),
            ),
          ],
        ),
      ),
    );
  }
  Widget _buildDownloadsList({required bool useSidebar}) {
    final service = DownloadService.instance;
    return AnimatedBuilder(
      animation: service,
      builder: (context, _) {
        if (!service.isReady) {
          return const Center(
            child: Text(
              '当前设备暂不支持离线下载',
              style: TextStyle(color: AppleColors.labelSecondary, fontSize: 14),
            ),
          );
        }
        final tasks = service.tasks;
        if (tasks.isEmpty) {
          return const Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  CupertinoIcons.arrow_down_circle,
                  color: AppleColors.labelTertiary,
                  size: 46,
                ),
                SizedBox(height: 12),
                Text(
                  '还没有下载任何影片',
                  style: TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                SizedBox(height: 6),
                Text(
                  '在影片详情页点击「下载」，即可在没有网络时观看',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: AppleColors.labelSecondary,
                    fontSize: 13,
                  ),
                ),
              ],
            ),
          );
        }
        final active = service.activeCount;
        final hasPaused = tasks.any((t) => t.status == DownloadStatus.paused);
        return ListView(
          padding: EdgeInsets.only(bottom: useSidebar ? 24 : 124),
          children: [
            if (active > 0 || hasPaused)
              Padding(
                padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
                child: Row(
                  children: [
                    Text(
                      active > 0 ? '$active 个任务进行中' : '下载已暂停',
                      style: const TextStyle(
                        color: AppleColors.labelSecondary,
                        fontSize: 13,
                      ),
                    ),
                    const Spacer(),
                    CupertinoButton(
                      padding: EdgeInsets.zero,
                      minSize: 26,
                      onPressed:
                          active > 0 ? service.pauseAll : service.resumeAll,
                      child: Text(
                        active > 0 ? '全部暂停' : '全部继续',
                        style: const TextStyle(
                          color: AppleColors.systemBlue,
                          fontSize: 13,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            AppleGroupedSection(
              children: [for (final t in tasks) _buildDownloadRow(t)],
            ),
            if (service.rootPath.isNotEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 10, 16, 0),
                child: Text(
                  '保存位置：${service.rootPath}',
                  style: const TextStyle(
                    color: AppleColors.labelTertiary,
                    fontSize: 11,
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
  Widget _buildDownloadRow(DownloadTask t) {
    final service = DownloadService.instance;
    final done = t.status == DownloadStatus.completed;
    final failed = t.status == DownloadStatus.failed;
    final statusColor = failed
        ? AppleColors.systemRed
        : (done ? AppleColors.systemGreen : AppleColors.labelSecondary);
    IconData actionIcon;
    VoidCallback action;
    if (done) {
      actionIcon = CupertinoIcons.play_circle_fill;
      action = () => _playDownloaded(t);
    } else if (t.status == DownloadStatus.paused || failed) {
      actionIcon = CupertinoIcons.arrow_clockwise_circle;
      action = () => service.resume(t.id);
    } else {
      actionIcon = CupertinoIcons.pause_circle;
      action = () => service.pause(t.id);
    }
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: done ? () => _playDownloaded(t) : null,
        highlightColor: Colors.white10,
        splashColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(7),
                child: SizedBox(
                  width: 40,
                  height: 56,
                  child: PosterImage(
                    url: t.poster,
                    title: t.title,
                    placeholder: _buildPosterPlaceholder(),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      t.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppleColors.labelPrimary,
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      '${t.episodeName} · ${t.lineName}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppleColors.labelSecondary,
                        fontSize: 12,
                      ),
                    ),
                    const SizedBox(height: 6),
                    if (!done) ...[
                      ClipRRect(
                        borderRadius: BorderRadius.circular(2),
                        child: LinearProgressIndicator(
                          value: t.status == DownloadStatus.queued &&
                                  t.progress <= 0
                              ? null
                              : t.progress.clamp(0.0, 1.0),
                          minHeight: 3,
                          backgroundColor: AppleColors.fill,
                          valueColor: AlwaysStoppedAnimation<Color>(
                            failed
                                ? AppleColors.systemRed
                                : AppleColors.systemBlue,
                          ),
                        ),
                      ),
                      const SizedBox(height: 4),
                    ],
                    Text(
                      DownloadService.statusLabel(t),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(color: statusColor, fontSize: 11),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 6),
              CupertinoButton(
                padding: const EdgeInsets.all(4),
                minSize: 34,
                onPressed: action,
                child: Icon(
                  actionIcon,
                  size: 27,
                  color: done
                      ? AppleColors.labelPrimary
                      : AppleColors.systemBlue,
                ),
              ),
              CupertinoButton(
                padding: const EdgeInsets.all(4),
                minSize: 34,
                onPressed: () => _confirmDeleteDownload(t),
                child: const Icon(
                  CupertinoIcons.trash,
                  size: 19,
                  color: AppleColors.labelTertiary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
  void _confirmDeleteDownload(DownloadTask t) {
    showCupertinoDialog<void>(
      context: context,
      barrierDismissible: true,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('删除下载？'),
        content: Text('「${t.title} · ${t.episodeName}」及已下载的文件将被删除。'),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('取消'),
          ),
          CupertinoDialogAction(
            isDestructiveAction: true,
            onPressed: () {
              Navigator.of(ctx).pop();
              DownloadService.instance.remove(t.id);
            },
            child: const Text('删除'),
          ),
        ],
      ),
    );
  }
  void _playDownloaded(DownloadTask t) {
    final completed = DownloadService.instance
        .completedFor(t.siteKey, t.videoId)
        .where((x) => x.lineName == t.lineName && x.filePath.isNotEmpty)
        .toList();
    if (completed.isEmpty) return;
    final start = completed.indexWhere((x) => x.id == t.id);
    final site = CmsSite(
      key: t.siteKey,
      name: t.siteName,
      type: t.siteType,
      api: t.siteApi,
    );
    final video = CmsVideo(
      id: t.videoId,
      title: t.title,
      poster: t.poster,
      lines: [
        CmsLine(
          name: t.lineName,
          episodes: [
            for (final x in completed)
              CmsEpisode(name: x.episodeName, url: x.filePath),
          ],
        ),
      ],
    );
    Navigator.of(context).push(
      CupertinoPageRoute<void>(
        builder: (_) => PlayerScreen(
          site: site,
          video: video,
          initialLineIndex: 0,
          initialEpisodeIndex: start < 0 ? 0 : start,
          storage: widget.storage,
        ),
      ),
    );
  }
  Widget _buildSettingsTab(BuildContext context, {required bool useSidebar}) {
    final pad = useSidebar ? 28.0 : 16.0;
    final depots = _config?.depots ?? const [];
    final subs = widget.storage.getSubscriptions();
    final publicSubs = subs.where((s) => !s.isEffectivelyPrivate).toList();
    final publicSites = _config?.publicSupportedSites ?? const [];
    return SafeArea(
      bottom: useSidebar,
      child: ListView(
        padding: EdgeInsets.fromLTRB(
          pad,
          useSidebar && _isMacOS ? 28 : 14,
          pad,
          useSidebar ? 32 : 128,
        ),
        children: [
          const Text(
            '设置',
            style: TextStyle(
              color: AppleColors.labelPrimary,
              fontSize: 28,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.6,
            ),
          ),
          const SizedBox(height: 18),
          AppleGroupedSection(
            header: '片源与订阅分组管理',
            footer:
                '支持批量导入多个订阅资源并自定义分组，全面兼容 VOD 接口、M3U/TXT 直播与点播、TVBox 单仓/多仓 JSON。',
            children: [
              AppleGroupedTile(
                icon: CupertinoIcons.add_circled_solid,
                iconBgColor: AppleColors.systemBlue,
                title: '导入订阅资源（支持分组 / 批量导入）',
                subtitle: '兼容 VOD / M3U / TXT / JSON / 多仓，支持同时导入多个地址',
                onTap: () => _showImportConfigDialog(),
              ),
              AppleGroupedTile(
                icon: CupertinoIcons.folder_fill,
                iconBgColor: AppleColors.systemPurple,
                title: '订阅源与分组管理',
                subtitle: publicSubs.isEmpty
                    ? '管理已导入的多个订阅源与自定义分组'
                    : '已导入 ${publicSubs.length} 个订阅 · ${widget.storage.getSubscriptionGroups().length} 个分组',
                trailingText: '${publicSubs.length} 个',
                onTap: _showSubscriptionManagerSheet,
              ),
              AppleGroupedTile(
                icon: CupertinoIcons.doc_on_clipboard_fill,
                iconBgColor: AppleColors.systemIndigo,
                title: '一键从剪贴板导入',
                subtitle: '复制配置地址、M3U、VOD、JSON 或备份文本后直接点击即可生效',
                onTap: _quickImportFromClipboard,
              ),
              if (depots.isNotEmpty)
                AppleGroupedTile(
                  icon: CupertinoIcons.archivebox_fill,
                  iconBgColor: AppleColors.systemOrange,
                  title: '切换多仓子仓库',
                  subtitle: widget.storage.activeDepotName.isEmpty
                      ? '共 ${depots.length} 个子仓库'
                      : '当前：${widget.storage.activeDepotName}',
                  trailingText: '${depots.length} 个',
                  onTap: _showDepotPickerDialog,
                ),
              if (publicSites.isNotEmpty)
                AppleGroupedTile(
                  icon: CupertinoIcons.layers_alt_fill,
                  iconBgColor: AppleColors.systemGreen,
                  title: '切换当前片源（按分组筛选）',
                  subtitle: _activeSite != null &&
                          !_activeSite!.isEffectivelyPrivate
                      ? '当前：${_activeSite!.name} (${_activeSite!.group})'
                      : '查看已导入站点列表',
                  trailingText: '${publicSites.length} 可用',
                  onTap: _showSitePickerDialog,
                ),
              AppleGroupedTile(
                icon: CupertinoIcons.qrcode_viewfinder,
                iconBgColor: AppleColors.systemBlue,
                title: '我的云盘 · 网盘扫码登录与配置',
                subtitle: '我的夸父（夸克）/ 我的优汐（UC）/ 我的嘟嘟（百度）/ 我的阿狸（阿里）',
                onTap: () => showNetdiskAccountSheet(
                  context,
                  widget.storage,
                  onChanged: () {
                    if (mounted) setState(() {});
                  },
                ),
              ),
            ],
          ),
          const SizedBox(height: 22),
          AppleGroupedSection(
            header: '多端同步与播放偏好',
            children: [
              if (DownloadService.instance.isReady)
                AppleGroupedTile(
                  icon: CupertinoIcons.arrow_down_circle_fill,
                  iconBgColor: AppleColors.systemBlue,
                  title: '离线下载',
                  subtitle: DownloadService.instance.rootPath.isEmpty
                      ? '下载的影片可在没有网络时观看'
                      : '保存位置：${DownloadService.instance.rootPath}',
                  trailingText: '${DownloadService.instance.tasks.length} 个',
                  onTap: () => setState(() {
                    _tabIndex = 2;
                    _librarySegment = 2;
                  }),
                ),
              AppleGroupedTile(
                icon: CupertinoIcons.arrow_2_circlepath,
                iconBgColor: AppleColors.systemGreen,
                title: '跨平台备份导出 / 恢复',
                subtitle: '与 Android 电视、手机、Windows、Mac 双向互通备份',
                onTap: _showBackupRestoreDialog,
              ),
              AppleGroupedTile(
                icon: CupertinoIcons.forward_end_fill,
                iconBgColor: AppleColors.systemBlue,
                title: '自动连播下一集',
                subtitle: '当前剧集播放完毕后在播放器内无缝切换下一集',
                trailing: CupertinoSwitch(
                  value: widget.storage.autoNext,
                  activeTrackColor: AppleColors.systemGreen,
                  onChanged: (v) async {
                    await widget.storage.setAutoNext(v);
                    setState(() {});
                  },
                ),
              ),
            ],
          ),
          const SizedBox(height: 28),
          Center(
            child: Column(
              children: [
                const MarcoBrandLogo(
                  iconSize: 52,
                  fontSize: 18,
                  stacked: true,
                ),
                const SizedBox(height: 6),
                const Text(
                  'Version 0.4.26 · Apple HIG Edition',
                  style: TextStyle(
                    color: AppleColors.labelSecondary,
                    fontSize: 12,
                  ),
                ),
                const SizedBox(height: 6),
                const Padding(
                  padding: EdgeInsets.symmetric(horizontal: 24),
                  child: Text(
                    '本产品仅为播放工具，不内置、不提供、不托管任何影视资源或配置地址。',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: AppleColors.labelTertiary,
                      fontSize: 11,
                      height: 1.4,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
  void _showSubscriptionManagerSheet() {
    showAppleModalSheet<void>(
      context: context,
      title: '订阅源与分组管理',
      trailingAction: CupertinoButton(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        minSize: 28,
        onPressed: () {
          Navigator.of(context).pop();
          _showImportConfigDialog();
        },
        child: const Text(
          '+ 批量导入',
          style: TextStyle(color: AppleColors.systemBlue, fontSize: 13.5),
        ),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) {
          final subs = widget.storage
              .getSubscriptions()
              .where((s) => !s.isEffectivelyPrivate)
              .toList();
          if (subs.isEmpty) {
            return Padding(
              padding: const EdgeInsets.all(28),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(
                    CupertinoIcons.folder_badge_plus,
                    color: AppleColors.labelTertiary,
                    size: 44,
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    '暂未添加多订阅分组',
                    style: TextStyle(
                      color: AppleColors.labelPrimary,
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    '支持同时导入多个 VOD / M3U / JSON 订阅地址，并按分组归类管理。',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: AppleColors.labelSecondary,
                      fontSize: 13,
                    ),
                  ),
                  const SizedBox(height: 18),
                  AppleCapsuleButton(
                    label: '立即导入订阅',
                    icon: CupertinoIcons.add,
                    primary: true,
                    compact: true,
                    onPressed: () {
                      Navigator.of(ctx).pop();
                      _showImportConfigDialog();
                    },
                  ),
                ],
              ),
            );
          }
          final byGroup = <String, List<SubscriptionEntry>>{};
          for (final s in subs) {
            byGroup.putIfAbsent(s.group, () => []).add(s);
          }
          return ListView(
            shrinkWrap: true,
            padding: const EdgeInsets.all(16),
            children: [
              for (final entry in byGroup.entries) ...[
                AppleGroupedSection(
                  header: '${entry.key} (${entry.value.length})',
                  children: entry.value.map((sub) {
                    return AppleGroupedTile(
                      icon: sub.isPrivate
                          ? CupertinoIcons.lock_shield_fill
                          : CupertinoIcons.link,
                      iconBgColor: sub.enabled
                          ? AppleColors.systemBlue
                          : AppleColors.groupedTertiary,
                      title: '${sub.name} · [${sub.format}]',
                      subtitle:
                          '${sub.siteCount} 片源 · ${sub.liveCount} 直播${sub.url.isNotEmpty ? " · ${sub.url}" : ""}',
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          CupertinoSwitch(
                            value: sub.enabled,
                            activeTrackColor: AppleColors.systemGreen,
                            onChanged: (v) async {
                              await widget.storage.toggleSubscriptionEnabled(
                                sub.id,
                                v,
                              );
                              _reloadSavedConfig();
                              setSheetState(() {});
                            },
                          ),
                          CupertinoButton(
                            padding: const EdgeInsets.only(left: 6),
                            minSize: 28,
                            onPressed: () async {
                              await widget.storage.removeSubscription(sub.id);
                              _reloadSavedConfig();
                              setSheetState(() {});
                            },
                            child: const Icon(
                              CupertinoIcons.trash,
                              color: AppleColors.systemRed,
                              size: 18,
                            ),
                          ),
                        ],
                      ),
                    );
                  }).toList(),
                ),
                const SizedBox(height: 14),
              ],
            ],
          );
        },
      ),
    );
  }
  void _showDepotPickerDialog() {
    final depots = _config?.depots ?? const [];
    if (depots.isEmpty) return;
    showAppleModalSheet<void>(
      context: context,
      title: '切换多仓子仓库 (${depots.length})',
      builder: (ctx) {
        bool loading = false;
        String? errorMsg;
        return StatefulBuilder(
          builder: (ctx, setModalState) {
            return Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (loading)
                  const Padding(
                    padding: EdgeInsets.all(14),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        CupertinoActivityIndicator(radius: 10),
                        SizedBox(width: 10),
                        Text(
                          '正在加载所选子仓库…',
                          style: TextStyle(
                            color: AppleColors.labelSecondary,
                            fontSize: 13,
                          ),
                        ),
                      ],
                    ),
                  ),
                if (errorMsg != null)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 10, 16, 4),
                    child: Text(
                      errorMsg!,
                      style: const TextStyle(
                        color: AppleColors.systemRed,
                        fontSize: 13,
                      ),
                    ),
                  ),
                Flexible(
                  child: ListView.separated(
                    shrinkWrap: true,
                    padding: const EdgeInsets.all(16),
                    itemCount: depots.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 8),
                    itemBuilder: (ctx, i) {
                      final d = depots[i];
                      final isCurrent = d.url == widget.storage.configOrigin ||
                          d.name == widget.storage.activeDepotName;
                      return Container(
                        decoration: BoxDecoration(
                          color: isCurrent
                              ? const Color(0x330A84FF)
                              : AppleColors.groupedSecondary,
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: AppleGroupedTile(
                          icon: CupertinoIcons.archivebox_fill,
                          iconBgColor: isCurrent
                              ? AppleColors.systemBlue
                              : AppleColors.groupedTertiary,
                          title: d.name,
                          subtitle: d.url,
                          trailing: isCurrent
                              ? const Icon(
                                  CupertinoIcons.checkmark_alt,
                                  color: AppleColors.systemBlue,
                                  size: 18,
                                )
                              : null,
                          onTap: loading
                              ? null
                              : () async {
                                  setModalState(() {
                                    loading = true;
                                    errorMsg = null;
                                  });
                                  try {
                                    final raw = await widget.client
                                        .fetchRawConfig(d.url);
                                    await widget.storage.saveConfig(
                                      raw,
                                      origin: d.url,
                                      depots: depots,
                                      activeDepotName: d.name,
                                    );
                                    if (ctx.mounted) Navigator.of(ctx).pop();
                                    _reloadSavedConfig();
                                  } catch (e) {
                                    setModalState(() {
                                      loading = false;
                                      errorMsg = '切换仓库失败：$e';
                                    });
                                  }
                                },
                        ),
                      );
                    },
                  ),
                ),
              ],
            );
          },
        );
      },
    );
  }
  void _showSitePickerDialog() {
    final supported = _config?.publicSupportedSites ?? const <CmsSite>[];
    final groups = <String>['全部'];
    for (final s in supported) {
      if (s.isEffectivelyPrivate) continue;
      final g = s.group.trim().isEmpty ? '默认分组' : s.group.trim();
      if (!groups.contains(g)) groups.add(g);
    }
    String selectedGroup = '全部';
    final plugins = (_config?.pluginOnlySites ?? const <CmsSite>[])
        .where((s) => !s.isEffectivelyPrivate)
        .toList();
    showAppleModalSheet<void>(
      context: context,
      title: '选择片源 (${supported.length} 可用)',
      trailingAction: CupertinoButton(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        minSize: 28,
        onPressed: () {
          Navigator.of(context).pop();
          _showImportConfigDialog();
        },
        child: const Text(
          '+ 导入订阅',
          style: TextStyle(color: AppleColors.systemBlue, fontSize: 13.5),
        ),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setPickerState) {
          final filteredSites = selectedGroup == '全部'
              ? supported
              : supported
                  .where(
                    (s) =>
                        (s.group.trim().isEmpty ? '默认分组' : s.group.trim()) ==
                        selectedGroup,
                  )
                  .toList();
          final directSites =
              filteredSites.where((s) => !s.isScriptOrJarApi).toList();
          final bridgedSites =
              filteredSites.where((s) => s.isScriptOrJarApi).toList();
          return ListView(
            shrinkWrap: true,
            padding: const EdgeInsets.all(16),
            children: [
              if (groups.length > 1) ...[
                SizedBox(
                  height: 32,
                  child: ListView.separated(
                    scrollDirection: Axis.horizontal,
                    itemCount: groups.length,
                    separatorBuilder: (_, __) => const SizedBox(width: 8),
                    itemBuilder: (ctx, idx) {
                      final g = groups[idx];
                      return _buildMiniFilterChip(
                        label: g,
                        selected: selectedGroup == g,
                        onTap: () => setPickerState(() => selectedGroup = g),
                      );
                    },
                  ),
                ),
                const SizedBox(height: 14),
              ],
              if (directSites.isNotEmpty) ...[
                AppleGroupedSection(
                  header: '直连 VOD / M3U / HTTP 片源 (${directSites.length})',
                  children: directSites.map((s) {
                    final selected = s.key == _activeSite?.key;
                    return AppleGroupedTile(
                      icon: CupertinoIcons.tv_fill,
                      iconBgColor: selected
                          ? AppleColors.systemBlue
                          : AppleColors.groupedTertiary,
                      title: s.name,
                      subtitle: '${s.group} · ${s.api}',
                      trailing: selected
                          ? const Icon(
                              CupertinoIcons.checkmark_alt,
                              color: AppleColors.systemBlue,
                              size: 18,
                            )
                          : null,
                      onTap: () {
                        Navigator.of(ctx).pop();
                        widget.storage.setSelectedSiteKey(s.key);
                        setState(() {
                          _privateSessionUnlocked = false;
                          _activeSite = s;
                        });
                        _loadSiteHome();
                      },
                    );
                  }).toList(),
                ),
                if (bridgedSites.isNotEmpty) const SizedBox(height: 16),
              ],
              if (bridgedSites.isNotEmpty)
                AppleGroupedSection(
                  header: 'Apple 跨平台智能解析片源 (${bridgedSites.length})',
                  footer: '已通过内置跨平台解析引擎自动适配 csp_* 与 JS 站点，点击即可浏览、搜索与播放。',
                  children: bridgedSites.map((s) {
                    final selected = s.key == _activeSite?.key;
                    return AppleGroupedTile(
                      icon: CupertinoIcons.sparkles,
                      iconBgColor: selected
                          ? AppleColors.systemBlue
                          : AppleColors.systemIndigo,
                      title: s.name,
                      subtitle: '${s.group} · Apple 跨平台智能解析 · ${s.api}',
                      trailing: selected
                          ? const Icon(
                              CupertinoIcons.checkmark_alt,
                              color: AppleColors.systemBlue,
                              size: 18,
                            )
                          : null,
                      onTap: () {
                        Navigator.of(ctx).pop();
                        widget.storage.setSelectedSiteKey(s.key);
                        setState(() {
                          _privateSessionUnlocked = false;
                          _activeSite = s;
                        });
                        _loadSiteHome();
                      },
                    );
                  }).toList(),
                ),
              if (plugins.isNotEmpty && selectedGroup == '全部') ...[
                const SizedBox(height: 16),
                AppleGroupedSection(
                  header: '其他未识别插件站点 (${plugins.length})',
                  children: plugins.take(25).map((s) {
                    return AppleGroupedTile(
                      icon: CupertinoIcons.lock_shield_fill,
                      iconBgColor: AppleColors.groupedTertiary,
                      title: s.name,
                      subtitle: s.unsupportedReason,
                    );
                  }).toList(),
                ),
              ],
            ],
          );
        },
      ),
    );
  }
  void _showImportConfigDialog({
    String? initialInput,
    String? initialError,
    String initialGroup = '默认分组',
    bool initialIsPrivate = false,
  }) {
    final urlCtrl = TextEditingController(
      text: initialInput ??
          (initialIsPrivate ? '' : widget.storage.configOrigin),
    );
    final jsonCtrl = TextEditingController();
    final groupCtrl = TextEditingController(text: initialGroup);
    bool isPrivate = initialIsPrivate;
    bool submitting = false;
    String? errorText = initialError;
    final showPrivacyToggle =
        initialIsPrivate || !widget.storage.hasPrivacyConfigured;
    const presetGroups = ['默认分组', '主力影视', '4K网盘', '电视直播', '动漫番剧'];
    showAppleModalSheet<void>(
      context: context,
      title: initialIsPrivate
          ? '导入隐私订阅资源'
          : '导入订阅资源（支持分组 / 批量导入）',
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) {
          Future<void> handleImport() async {
            setModalState(() {
              submitting = true;
              errorText = null;
            });
            try {
              final urlText = urlCtrl.text.trim();
              final jsonText = jsonCtrl.text.trim();
              final selectedGroup = groupCtrl.text.trim().isEmpty
                  ? '默认分组'
                  : groupCtrl.text.trim();
              final effectivePrivate = isPrivate ||
                  selectedGroup.contains('隐私') ||
                  selectedGroup.contains('私密');
              final candidateBackup =
                  jsonText.isNotEmpty ? jsonText : urlText;
              if (CmsParser.isBackupPayload(candidateBackup)) {
                await widget.storage.importBackupJson(candidateBackup);
                if (ctx.mounted) Navigator.of(ctx).pop();
                _reloadSavedConfig();
                return;
              }
              final entries = await widget.client.resolveBatchSubscriptions(
                urlInput: urlText,
                rawTextInput: jsonText,
                defaultGroup: selectedGroup,
                isPrivate: effectivePrivate,
              );
              await widget.storage.upsertSubscriptions(entries);
              if (!initialIsPrivate) {
                _privateSessionUnlocked = false;
              }
              if (ctx.mounted) Navigator.of(ctx).pop();
              _reloadSavedConfig();
              if (effectivePrivate && !widget.storage.hasPrivacyPassword) {
                _showSetPrivacyPasswordDialog(
                  title: '设置隐私访问密码',
                  subtitle: '请为隐私分组设置访问密码：',
                );
              }
            } catch (e) {
              setModalState(() {
                submitting = false;
                errorText = '导入失败：$e';
              });
            }
          }
          return SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                // 1. Group Selector
                const Text(
                  '订阅分组（可选择预设或直接输入自定义分组名）',
                  style: TextStyle(
                    color: AppleColors.labelSecondary,
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final g in presetGroups)
                      _buildMiniFilterChip(
                        label: g,
                        selected: groupCtrl.text.trim() == g,
                        onTap: () => setModalState(() => groupCtrl.text = g),
                      ),
                  ],
                ),
                const SizedBox(height: 8),
                CupertinoTextField(
                  controller: groupCtrl,
                  placeholder: '输入自定义分组名称（如：主力4K、少儿专区）',
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 10,
                  ),
                  style: const TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 13.5,
                  ),
                  placeholderStyle: const TextStyle(
                    color: AppleColors.labelTertiary,
                    fontSize: 13,
                  ),
                  onChanged: (_) => setModalState(() {}),
                  decoration: BoxDecoration(
                    color: AppleColors.groupedSecondary,
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(
                      color: AppleColors.glassBorder,
                      width: 0.5,
                    ),
                  ),
                ),
                const SizedBox(height: 14),
                if (showPrivacyToggle) ...[
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    decoration: BoxDecoration(
                      color: AppleColors.groupedSecondary,
                      borderRadius: BorderRadius.circular(11),
                      border: Border.all(
                        color: AppleColors.glassBorder,
                        width: 0.5,
                      ),
                    ),
                    child: Row(
                      children: [
                        const Icon(
                          CupertinoIcons.lock_shield_fill,
                          color: AppleColors.systemPurple,
                          size: 18,
                        ),
                        const SizedBox(width: 8),
                        const Expanded(
                          child: Text(
                            '设为隐私分组',
                            style: TextStyle(
                              color: AppleColors.labelPrimary,
                              fontSize: 13.5,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        CupertinoSwitch(
                          value: isPrivate,
                          activeTrackColor: AppleColors.systemPurple,
                          onChanged: (v) => setModalState(() {
                            isPrivate = v;
                            if (v && groupCtrl.text.trim() == '默认分组') {
                              groupCtrl.text = '隐私分组';
                            } else if (!v &&
                                groupCtrl.text.trim() == '隐私分组') {
                              groupCtrl.text = '默认分组';
                            }
                          }),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 14),
                ],
                // 3. Multi-subscription URLs input
                Row(
                  children: [
                    const Expanded(
                      child: Text(
                        '订阅地址（支持多行批量导入 · 兼容 VOD / M3U / JSON / 多仓）',
                        style: TextStyle(
                          color: AppleColors.labelSecondary,
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    CupertinoButton(
                      padding: EdgeInsets.zero,
                      minSize: 26,
                      onPressed: () async {
                        final data =
                            await Clipboard.getData(Clipboard.kTextPlain);
                        final clip = (data?.text ?? '').trim();
                        if (clip.isEmpty) return;
                        if (clip.startsWith('{') ||
                            clip.startsWith('[') ||
                            clip.startsWith('#EXTM3U') ||
                            clip.startsWith('<')) {
                          jsonCtrl.text = clip;
                        } else {
                          urlCtrl.text = clip;
                        }
                        setModalState(() => errorText = null);
                      },
                      child: const Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            CupertinoIcons.doc_on_clipboard,
                            size: 14,
                            color: AppleColors.systemBlue,
                          ),
                          SizedBox(width: 4),
                          Text(
                            '粘贴剪贴板',
                            style: TextStyle(
                              color: AppleColors.systemBlue,
                              fontSize: 13,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                CupertinoTextField(
                  controller: urlCtrl,
                  placeholder:
                      '每行一个订阅地址（如：https://config.example/config.json）\n支持 JSON单仓/多仓、MacCMS VOD接口、M3U/M3U8/TXT 直播与点播',
                  maxLines: 4,
                  minLines: 2,
                  autocorrect: false,
                  enableSuggestions: false,
                  smartQuotesType: SmartQuotesType.disabled,
                  smartDashesType: SmartDashesType.disabled,
                  keyboardType: TextInputType.multiline,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 10,
                  ),
                  style: const TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 13.5,
                  ),
                  placeholderStyle: const TextStyle(
                    color: AppleColors.labelTertiary,
                    fontSize: 12.5,
                  ),
                  decoration: BoxDecoration(
                    color: AppleColors.groupedSecondary,
                    borderRadius: BorderRadius.circular(11),
                    border: Border.all(
                      color: AppleColors.glassBorder,
                      width: 0.5,
                    ),
                  ),
                ),
                const SizedBox(height: 14),
                const Text(
                  '或直接粘贴 JSON / M3U (#EXTM3U) / VOD / Base64 内容（选填）',
                  style: TextStyle(
                    color: AppleColors.labelSecondary,
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 8),
                CupertinoTextField(
                  controller: jsonCtrl,
                  placeholder:
                      '{"sites": [...]} 或 #EXTM3U 播放列表 或 频道名,https://live.example/tv.m3u8',
                  maxLines: 4,
                  minLines: 2,
                  autocorrect: false,
                  enableSuggestions: false,
                  smartQuotesType: SmartQuotesType.disabled,
                  smartDashesType: SmartDashesType.disabled,
                  padding: const EdgeInsets.all(12),
                  style: const TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 13,
                  ),
                  placeholderStyle: const TextStyle(
                    color: AppleColors.labelTertiary,
                    fontSize: 12.5,
                  ),
                  decoration: BoxDecoration(
                    color: AppleColors.groupedSecondary,
                    borderRadius: BorderRadius.circular(11),
                    border: Border.all(
                      color: AppleColors.glassBorder,
                      width: 0.5,
                    ),
                  ),
                ),
                if (errorText != null) ...[
                  const SizedBox(height: 14),
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: const Color(0x26FF453A),
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                        color: const Color(0x66FF453A),
                        width: 0.5,
                      ),
                    ),
                    child: Text(
                      errorText!,
                      style: const TextStyle(
                        color: AppleColors.systemRed,
                        fontSize: 13,
                        height: 1.4,
                      ),
                    ),
                  ),
                ],
                const SizedBox(height: 18),
                SizedBox(
                  width: double.infinity,
                  child: submitting
                      ? const Center(
                          child: Padding(
                            padding: EdgeInsets.symmetric(vertical: 10),
                            child: CupertinoActivityIndicator(radius: 12),
                          ),
                        )
                      : AppleCapsuleButton(
                          label: isPrivate ? '保存至隐私隐藏列表' : '导入并启用订阅',
                          icon: isPrivate
                              ? CupertinoIcons.lock_fill
                              : CupertinoIcons.checkmark_circle_fill,
                          primary: true,
                          onPressed: handleImport,
                        ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
  void _showBackupRestoreDialog() {
    final ctrl = TextEditingController(text: widget.storage.exportBackupJson());
    String? errorText;
    showAppleModalSheet<void>(
      context: context,
      title: '跨平台备份导出 / 恢复',
      trailingAction: CupertinoButton(
        padding: const EdgeInsets.symmetric(horizontal: 10),
        minSize: 28,
        onPressed: () {
          Clipboard.setData(ClipboardData(text: ctrl.text));
          _showAppleNotice('已复制备份', '备份 JSON 已复制到剪贴板，可发送至其他设备恢复。');
        },
        child: const Text(
          '复制导出',
          style: TextStyle(color: AppleColors.systemBlue, fontSize: 14),
        ),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) => SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                '与 Android 电视/手机端 MarcoMovie-1 备份格式双向兼容：',
                style: TextStyle(
                  color: AppleColors.labelSecondary,
                  fontSize: 12,
                ),
              ),
              const SizedBox(height: 10),
              CupertinoTextField(
                controller: ctrl,
                maxLines: 9,
                minLines: 6,
                autocorrect: false,
                enableSuggestions: false,
                smartQuotesType: SmartQuotesType.disabled,
                smartDashesType: SmartDashesType.disabled,
                padding: const EdgeInsets.all(12),
                style: const TextStyle(
                  color: AppleColors.labelPrimary,
                  fontSize: 12,
                ),
                decoration: BoxDecoration(
                  color: AppleColors.groupedSecondary,
                  borderRadius: BorderRadius.circular(11),
                  border: Border.all(
                    color: AppleColors.glassBorder,
                    width: 0.5,
                  ),
                ),
              ),
              if (errorText != null) ...[
                const SizedBox(height: 12),
                Text(
                  errorText!,
                  style: const TextStyle(
                    color: AppleColors.systemRed,
                    fontSize: 13,
                  ),
                ),
              ],
              const SizedBox(height: 16),
              SizedBox(
                width: double.infinity,
                child: AppleCapsuleButton(
                  label: '从此备份文本恢复',
                  icon: CupertinoIcons.arrow_counterclockwise,
                  primary: true,
                  onPressed: () async {
                    try {
                      await widget.storage.importBackupJson(ctrl.text);
                      if (ctx.mounted) Navigator.of(ctx).pop();
                      _reloadSavedConfig();
                    } catch (e) {
                      setModalState(() => errorText = '恢复备份失败：$e');
                    }
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
