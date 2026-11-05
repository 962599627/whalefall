import 'dart:async';
import 'dart:io';
import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../core/cms_client.dart';
import '../core/cms_parser.dart';
import '../core/models.dart';
import '../core/storage_service.dart';
import 'apple_theme.dart';
import 'detail_screen.dart';
import 'player_screen.dart';
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
  List<CmsVideo> _homeVideos = const [];
  int _currentPage = 1;
  int _pageCount = 1;
  bool _homeLoading = false;
  String? _homeError;
  int _homeEpoch = 0;
  int _categoryEpoch = 0;
  // Search state
  final TextEditingController _searchController = TextEditingController();
  final List<(CmsSite, CmsVideo)> _searchResults = [];
  bool _searching = false;
  int _searchEpoch = 0;
  StreamSubscription<(CmsSite, List<CmsVideo>)>? _searchSubscription;
  // Live state
  int _selectedLiveSource = 0;
  List<LiveGroup> _liveGroups = const [];
  bool _liveLoading = false;
  String? _liveError;
  int _liveEpoch = 0;
  // Favorites / History segmented index
  int _librarySegment = 0;
  bool get _isMacOS => !kIsWeb && Platform.isMacOS;
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
    if (saved != null && saved.supportedSites.isNotEmpty) {
      final preferredKey = widget.storage.selectedSiteKey;
      _activeSite = saved.supportedSites.firstWhere(
        (s) => s.key == preferredKey,
        orElse: () => saved.supportedSites.first,
      );
      _loadSiteHome();
    } else {
      _activeSite = null;
      _categories = const [];
      _homeVideos = const [];
      _homeError = null;
    }
    if (saved != null && saved.lives.isNotEmpty) {
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
      _selectedCategoryId = '';
      _currentPage = 1;
    });
    try {
      final (cats, listing) = await widget.client.fetchHome(site);
      if (!mounted || epoch != _homeEpoch || _activeSite?.key != site.key) {
        return;
      }
      setState(() {
        _categories = cats;
        _homeVideos = listing.videos;
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
  Future<void> _loadCategory(String categoryId, {int page = 1}) async {
    final site = _activeSite;
    if (site == null) return;
    final epoch = ++_categoryEpoch;
    setState(() {
      _homeLoading = true;
      _homeError = null;
      _selectedCategoryId = categoryId;
    });
    try {
      final listing =
          await widget.client.fetchCategory(site, categoryId, page);
      if (!mounted ||
          epoch != _categoryEpoch ||
          _activeSite?.key != site.key ||
          _selectedCategoryId != categoryId) {
        return;
      }
      setState(() {
        _homeVideos = page == 1
            ? listing.videos
            : [..._homeVideos, ...listing.videos];
        _currentPage = listing.page;
        _pageCount = listing.pageCount;
        _homeLoading = false;
      });
    } catch (e) {
      if (!mounted ||
          epoch != _categoryEpoch ||
          _activeSite?.key != site.key ||
          _selectedCategoryId != categoryId) {
        return;
      }
      setState(() {
        _homeLoading = false;
        _homeError = '加载分类失败：$e';
      });
    }
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
    _searchSubscription = widget.client
        .searchAcrossSites(_config!.supportedSites, trimmed)
        .listen(
          (batch) {
            if (!mounted || epoch != _searchEpoch) return;
            final (site, videos) = batch;
            setState(() {
              for (final v in videos) {
                _searchResults.add((site, v));
              }
            });
          },
          onError: (_) {
            if (mounted && epoch == _searchEpoch) {
              setState(() => _searching = false);
            }
          },
          onDone: () {
            if (mounted && epoch == _searchEpoch) {
              setState(() => _searching = false);
            }
          },
        );
  }
  Future<void> _loadLivePlaylist(int sourceIndex) async {
    final lives = _config?.lives ?? const [];
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
      final resolved = await widget.client.resolveConfigInput(
        rawTextInput: text,
      );
      await widget.storage.saveConfig(
        resolved.rawConfig,
        origin: resolved.origin,
        depots: resolved.depots,
        activeDepotName: resolved.activeDepotName,
      );
      _reloadSavedConfig();
      final siteCount = resolved.config.supportedSites.length;
      final pluginCount = resolved.config.pluginOnlySites.length;
      final liveCount = resolved.config.lives.length;
      _showAppleNotice(
        '配置导入成功',
        '已识别 $siteCount 个直连片源'
        '${liveCount > 0 ? "、$liveCount 个直播源" : ""}'
        '${pluginCount > 0 ? "（另有 $pluginCount 个安卓 JAR 插件源）" : ""}。',
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
      (CupertinoIcons.search, CupertinoIcons.search, '搜索'),
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
      _buildSearchTab(context, useSidebar: isLandscape),
      _buildLiveTab(context, useSidebar: isLandscape),
      _buildFavoritesHistoryTab(context, useSidebar: isLandscape),
      _buildSettingsTab(context, useSidebar: isLandscape),
    ];
    return Scaffold(
      backgroundColor: AppleColors.background,
      extendBody: true,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // Ambient Apple Liquid Glass mesh background
          Container(
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color(0xFF141C2E),
                  Color(0xFF0A0D16),
                  Color(0xFF040508),
                ],
                stops: [0.0, 0.48, 1.0],
              ),
            ),
          ),
          Positioned(
            top: -120,
            left: -80,
            child: IgnorePointer(
              child: Container(
                width: 340,
                height: 340,
                decoration: const BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: RadialGradient(
                    colors: [
                      Color(0x360A84FF),
                      Color(0x125E5CE6),
                      Color(0x00000000),
                    ],
                  ),
                ),
              ),
            ),
          ),
          Positioned(
            bottom: -140,
            right: -60,
            child: IgnorePointer(
              child: Container(
                width: 320,
                height: 320,
                decoration: const BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: RadialGradient(
                    colors: [
                      Color(0x285E5CE6),
                      Color(0x1064D2FF),
                      Color(0x00000000),
                    ],
                  ),
                ),
              ),
            ),
          ),
          // Active page content (with right clearance in landscape for right-side Liquid Glass Dock)
          Padding(
            padding: EdgeInsets.only(right: isLandscape ? 88 : 0),
            child: pages[_tabIndex],
          ),
          // Apple Liquid Glass Floating Dock:
          // - Portrait: bottom center floating capsule dock
          // - Landscape: right-side vertical floating capsule dock
          if (isLandscape)
            Positioned(
              top: 0,
              bottom: 0,
              right: 0,
              child: Center(
                child: AppleLiquidGlassDock(
                  currentIndex: _tabIndex,
                  onTap: (i) => setState(() => _tabIndex = i),
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
                  currentIndex: _tabIndex,
                  onTap: (i) => setState(() => _tabIndex = i),
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
          SliverPadding(
            padding: EdgeInsets.fromLTRB(
              horizontalPad,
              useSidebar && _isMacOS ? 24 : 12,
              horizontalPad,
              12,
            ),
            sliver: SliverToBoxAdapter(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Top Header Bar
                  Row(
                    children: [
                      const MarcoBrandLogo(iconSize: 30, fontSize: 18),
                      const Spacer(),
                      if (depots.isNotEmpty) ...[
                        _buildHeaderCapsule(
                          icon: CupertinoIcons.archivebox_fill,
                          label: widget.storage.activeDepotName.isEmpty
                              ? '切换仓库'
                              : widget.storage.activeDepotName,
                          onTap: _showDepotPickerDialog,
                        ),
                        const SizedBox(width: 8),
                      ],
                      _buildHeaderCapsule(
                        icon: CupertinoIcons.layers_alt_fill,
                        label: _activeSite?.name ?? '选择片源',
                        onTap: _showSitePickerDialog,
                      ),
                      const SizedBox(width: 8),
                      CupertinoButton(
                        padding: EdgeInsets.zero,
                        minSize: 34,
                        onPressed: () => _showImportConfigDialog(),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(999),
                          child: BackdropFilter(
                            filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
                            child: Container(
                              width: 34,
                              height: 34,
                              decoration: BoxDecoration(
                                gradient: const LinearGradient(
                                  begin: Alignment.topLeft,
                                  end: Alignment.bottomRight,
                                  colors: [
                                    Color(0x48FFFFFF),
                                    Color(0x22262A36),
                                  ],
                                ),
                                borderRadius: BorderRadius.circular(999),
                                border: Border.all(
                                  color: AppleColors.glassBorderStrong,
                                  width: 0.8,
                                ),
                              ),
                              alignment: Alignment.center,
                              child: const Icon(
                                CupertinoIcons.add,
                                color: AppleColors.labelPrimary,
                                size: 18,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                  // If config has only Android JAR (`csp_*`) sites and no direct HTTP sites
                  if (_activeSite == null) ...[
                    const SizedBox(height: 24),
                    _buildPluginOnlyConfigNotice(context),
                  ] else ...[
                    // Up Next ("继续观看") Shelf
                    if (history.isNotEmpty) ...[
                      const SizedBox(height: 20),
                      Row(
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
                              _tabIndex = 3;
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
                      const SizedBox(height: 10),
                      SizedBox(
                        height: 86,
                        child: ListView.separated(
                          scrollDirection: Axis.horizontal,
                          itemCount: history.length.clamp(0, 8),
                          separatorBuilder: (_, __) =>
                              const SizedBox(width: 12),
                          itemBuilder: (ctx, i) =>
                              _buildContinueWatchingCard(history[i]),
                        ),
                      ),
                    ],
                    // Featured Hero Showcase Card on first page when videos exist
                    if (_homeVideos.isNotEmpty &&
                        _selectedCategoryId.isEmpty &&
                        _currentPage == 1) ...[
                      const SizedBox(height: 18),
                      _buildHeroShowcaseCard(
                        _activeSite!,
                        _homeVideos.first,
                        wide: useSidebar,
                      ),
                    ],
                    // Single-row Apple Category Pills
                    const SizedBox(height: 18),
                    SizedBox(
                      height: 36,
                      child: ListView.separated(
                        scrollDirection: Axis.horizontal,
                        itemCount: _categories.length + 1,
                        separatorBuilder: (_, __) => const SizedBox(width: 8),
                        itemBuilder: (ctx, idx) {
                          if (idx == 0) {
                            return _buildCategoryPill(
                              label: '精选推荐',
                              selected: _selectedCategoryId.isEmpty,
                              onTap: _loadSiteHome,
                            );
                          }
                          final cat = _categories[idx - 1];
                          return _buildCategoryPill(
                            label: cat.name,
                            selected: _selectedCategoryId == cat.id,
                            onTap: () => _loadCategory(cat.id),
                          );
                        },
                      ),
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
                  bottom: useSidebar ? 32 : 100,
                ),
                child: Center(
                  child: _homeLoading
                      ? const CupertinoActivityIndicator(radius: 13)
                      : (_currentPage < _pageCount
                          ? AppleCapsuleButton(
                              label: '加载更多影片',
                              icon: CupertinoIcons.arrow_down_circle,
                              onPressed: () => _loadCategory(
                                _selectedCategoryId,
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
                  onPressed: () => setState(() => _tabIndex = 2),
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
            constraints: const BoxConstraints(maxWidth: 164),
            padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 7.5),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color(0x42FFFFFF),
                  Color(0x26242834),
                  Color(0x36141720),
                ],
                stops: [0.0, 0.45, 1.0],
              ),
              borderRadius: BorderRadius.circular(999),
              border: Border.all(
                color: AppleColors.glassBorderStrong,
                width: 0.8,
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, color: AppleColors.systemBlue, size: 14),
                const SizedBox(width: 6),
                Flexible(
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppleColors.labelPrimary,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                const SizedBox(width: 4),
                const Icon(
                  CupertinoIcons.chevron_down,
                  color: AppleColors.labelSecondary,
                  size: 12,
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
    final subtitleParts = [
      featured.year,
      featured.area,
      featured.remarks,
    ].where((s) => s.isNotEmpty).join(' · ');
    return GestureDetector(
      onTap: () => _openVideoDetail(site, featured),
      child: Container(
        height: wide ? 260 : 214,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(24),
          color: AppleColors.groupedPrimary,
          border: Border.all(color: AppleColors.glassBorderStrong, width: 0.8),
          boxShadow: const [
            BoxShadow(
              color: Color(0x4D000000),
              blurRadius: 28,
              offset: Offset(0, 12),
            ),
          ],
        ),
        clipBehavior: Clip.antiAlias,
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (featured.poster.isNotEmpty)
              ImageFiltered(
                imageFilter: ImageFilter.blur(sigmaX: 32, sigmaY: 32),
                child: Image.network(
                  featured.poster,
                  headers: AppleColors.posterHeaders(featured.poster),
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                ),
              ),
            Container(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    Color(0x38FFFFFF),
                    Color(0xAA0A0D14),
                    Color(0xEB07090E),
                  ],
                  stops: [0.0, 0.45, 1.0],
                ),
              ),
            ),
            Padding(
              padding: EdgeInsets.all(wide ? 24 : 16),
              child: Row(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(14),
                    child: AspectRatio(
                      aspectRatio: 2 / 3,
                      child: featured.poster.isNotEmpty
                          ? Image.network(
                              featured.poster,
                              headers: AppleColors.posterHeaders(featured.poster),
                              fit: BoxFit.cover,
                              errorBuilder: (_, __, ___) =>
                                  _buildPosterPlaceholder(),
                            )
                          : _buildPosterPlaceholder(),
                    ),
                  ),
                  SizedBox(width: wide ? 22 : 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 9,
                            vertical: 3.5,
                          ),
                          decoration: BoxDecoration(
                            color: const Color(0x380A84FF),
                            borderRadius: BorderRadius.circular(999),
                            border: Border.all(
                              color: const Color(0x6664D2FF),
                              width: 0.6,
                            ),
                          ),
                          child: Text(
                            '今日首推 · ${site.name}',
                            style: const TextStyle(
                              color: Color(0xFF82C4FF),
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          featured.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: AppleColors.labelPrimary,
                            fontSize: wide ? 24 : 18,
                            fontWeight: FontWeight.w700,
                            letterSpacing: -0.4,
                          ),
                        ),
                        if (subtitleParts.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            subtitleParts,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: AppleColors.labelSecondary,
                              fontSize: 12,
                            ),
                          ),
                        ],
                        if (featured.description.isNotEmpty && wide) ...[
                          const SizedBox(height: 6),
                          Text(
                            featured.description,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: AppleColors.labelSecondary,
                              fontSize: 13,
                              height: 1.4,
                            ),
                          ),
                        ],
                        const SizedBox(height: 12),
                        AppleCapsuleButton(
                          label: '立即观看',
                          icon: CupertinoIcons.play_fill,
                          primary: true,
                          compact: true,
                          onPressed: () => _openVideoDetail(site, featured),
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
        borderRadius: BorderRadius.circular(16),
        padding: const EdgeInsets.all(10),
        blurSigma: 28,
        child: SizedBox(
          width: 214,
          child: Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(9),
                child: SizedBox(
                  width: 48,
                  height: 66,
                  child: entry.poster.isNotEmpty
                      ? Image.network(
                          entry.poster,
                          headers: AppleColors.posterHeaders(entry.poster),
                          fit: BoxFit.cover,
                          errorBuilder: (_, __, ___) =>
                              _buildPosterPlaceholder(),
                        )
                      : _buildPosterPlaceholder(),
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
      child: ClipRRect(
        borderRadius: BorderRadius.circular(999),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 7),
            decoration: BoxDecoration(
              gradient: selected
                  ? const LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [
                        Color(0xD84CA8FF),
                        Color(0xCC0A84FF),
                      ],
                    )
                  : const LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [
                        Color(0x36FFFFFF),
                        Color(0x22202430),
                      ],
                    ),
              borderRadius: BorderRadius.circular(999),
              border: Border.all(
                color: selected
                    ? AppleColors.glassHighlight
                    : AppleColors.glassBorder,
                width: 0.75,
              ),
            ),
            alignment: Alignment.center,
            child: Text(
              label,
              style: TextStyle(
                color: AppleColors.labelPrimary,
                fontSize: 13,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
              ),
            ),
          ),
        ),
      ),
    );
  }
  Widget _buildPosterPlaceholder() {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF232B3A), Color(0xFF12151E)],
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
  Widget _buildVideoCard(CmsSite site, CmsVideo video, {String? badge}) {
    return GestureDetector(
      onTap: () => _openVideoDetail(site, video),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(15),
                border: Border.all(
                  color: AppleColors.glassBorderStrong,
                  width: 0.7,
                ),
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x38000000),
                    blurRadius: 14,
                    offset: Offset(0, 6),
                  ),
                ],
              ),
              clipBehavior: Clip.antiAlias,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  video.poster.isNotEmpty
                      ? Image.network(
                          video.poster,
                          headers: AppleColors.posterHeaders(video.poster),
                          fit: BoxFit.cover,
                          errorBuilder: (_, __, ___) =>
                              _buildPosterPlaceholder(),
                        )
                      : _buildPosterPlaceholder(),
                  if (badge != null && badge.isNotEmpty)
                    Positioned(
                      top: 6,
                      left: 6,
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(999),
                        child: BackdropFilter(
                          filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 3,
                            ),
                            decoration: BoxDecoration(
                              color: const Color(0x9912151E),
                              borderRadius: BorderRadius.circular(999),
                              border: Border.all(
                                color: AppleColors.glassBorder,
                                width: 0.5,
                              ),
                            ),
                            child: Text(
                              badge,
                              style: const TextStyle(
                                color: AppleColors.labelPrimary,
                                fontSize: 10,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  if (video.remarks.isNotEmpty)
                    Positioned(
                      bottom: 6,
                      right: 6,
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(7),
                        child: BackdropFilter(
                          filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2.5,
                            ),
                            decoration: BoxDecoration(
                              color: const Color(0xAA161922),
                              borderRadius: BorderRadius.circular(7),
                              border: Border.all(
                                color: AppleColors.glassBorder,
                                width: 0.5,
                              ),
                            ),
                            child: Text(
                              video.remarks,
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
  Widget _buildSearchTab(BuildContext context, {required bool useSidebar}) {
    final history = widget.storage.getSearchHistory();
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
            const Text(
              '搜索',
              style: TextStyle(
                color: AppleColors.labelPrimary,
                fontSize: 28,
                fontWeight: FontWeight.w700,
                letterSpacing: -0.6,
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: CupertinoSearchTextField(
                    controller: _searchController,
                    placeholder: '搜索片名、演员或导演…',
                    backgroundColor: AppleColors.groupedPrimary,
                    style: const TextStyle(
                      color: AppleColors.labelPrimary,
                      fontSize: 15,
                    ),
                    placeholderStyle: const TextStyle(
                      color: AppleColors.labelSecondary,
                      fontSize: 15,
                    ),
                    onSubmitted: _runGlobalSearch,
                  ),
                ),
                const SizedBox(width: 10),
                AppleCapsuleButton(
                  label: '搜索',
                  primary: true,
                  compact: true,
                  onPressed: () => _runGlobalSearch(_searchController.text),
                ),
              ],
            ),
            if (history.isNotEmpty) ...[
              const SizedBox(height: 12),
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
                      },
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 5,
                        ),
                        decoration: BoxDecoration(
                          color: AppleColors.groupedPrimary,
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
            const SizedBox(height: 14),
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
                            size: 44,
                          ),
                          SizedBox(height: 10),
                          Text(
                            '输入关键词，并发检索全部直连片源',
                            style: TextStyle(
                              color: AppleColors.labelSecondary,
                              fontSize: 14,
                            ),
                          ),
                        ],
                      ),
                    )
                  : GridView.builder(
                      padding: EdgeInsets.only(bottom: useSidebar ? 24 : 96),
                      gridDelegate: SliverGridDelegateWithMaxCrossAxisExtent(
                        maxCrossAxisExtent: useSidebar ? 176 : 132,
                        mainAxisSpacing: 16,
                        crossAxisSpacing: 12,
                        childAspectRatio: 0.56,
                      ),
                      itemCount: _searchResults.length,
                      itemBuilder: (ctx, i) {
                        final (site, video) = _searchResults[i];
                        return _buildVideoCard(site, video, badge: site.name);
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }
  Widget _buildLiveTab(BuildContext context, {required bool useSidebar}) {
    final lives = _config?.lives ?? const [];
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
                  '导入包含 lives 节点的配置后，即可在此直接观看电视直播频道。',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: AppleColors.labelSecondary,
                    fontSize: 13,
                  ),
                ),
                const SizedBox(height: 18),
                AppleCapsuleButton(
                  label: '导入配置',
                  icon: CupertinoIcons.add,
                  primary: true,
                  compact: true,
                  onPressed: _showImportConfigDialog,
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
                  padding: EdgeInsets.only(bottom: useSidebar ? 24 : 96),
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
                            final liveSite = CmsSite(
                              key: 'live',
                              name: lives[_selectedLiveSource].name,
                              type: 1,
                              api: '',
                              headers: lives[_selectedLiveSource].headers,
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
                backgroundColor: AppleColors.groupedPrimary,
                thumbColor: AppleColors.groupedTertiary,
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
                },
                onValueChanged: (v) {
                  if (v != null) setState(() => _librarySegment = v);
                },
              ),
            ),
            const SizedBox(height: 16),
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
                            bottom: useSidebar ? 24 : 96,
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
                            bottom: useSidebar ? 24 : 96,
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
  Widget _buildSettingsTab(BuildContext context, {required bool useSidebar}) {
    final pad = useSidebar ? 28.0 : 16.0;
    final depots = _config?.depots ?? const [];
    return SafeArea(
      bottom: useSidebar,
      child: ListView(
        padding: EdgeInsets.fromLTRB(
          pad,
          useSidebar && _isMacOS ? 28 : 14,
          pad,
          useSidebar ? 32 : 100,
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
            header: '片源与仓库管理',
            footer: '支持单仓/多仓 HTTP(S) 接口、中文域名、Base64/图片隐写及加密配置。',
            children: [
              AppleGroupedTile(
                icon: CupertinoIcons.link,
                iconBgColor: AppleColors.systemBlue,
                title: '导入或更新配置',
                subtitle: widget.storage.configOrigin.isEmpty
                    ? '输入配置地址或直接粘贴 JSON 内容'
                    : widget.storage.configOrigin,
                onTap: _showImportConfigDialog,
              ),
              AppleGroupedTile(
                icon: CupertinoIcons.doc_on_clipboard_fill,
                iconBgColor: AppleColors.systemIndigo,
                title: '一键从剪贴板导入',
                subtitle: '复制配置地址、JSON 或备份文本后直接点击即可生效',
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
              if (_config != null && _config!.sites.isNotEmpty)
                AppleGroupedTile(
                  icon: CupertinoIcons.layers_alt_fill,
                  iconBgColor: AppleColors.systemGreen,
                  title: '切换当前片源',
                  subtitle: _activeSite != null
                      ? '当前：${_activeSite!.name}'
                      : '查看已导入站点列表',
                  trailingText: '${_config!.supportedSites.length} 可用',
                  onTap: _showSitePickerDialog,
                ),
            ],
          ),
          const SizedBox(height: 22),
          AppleGroupedSection(
            header: '多端同步与播放偏好',
            children: [
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
                  'Version 0.4.21 · Apple HIG Edition',
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
    final supported = _config?.supportedSites ?? const [];
    final directSites =
        supported.where((s) => !s.isScriptOrJarApi).toList();
    final bridgedSites =
        supported.where((s) => s.isScriptOrJarApi).toList();
    final plugins = _config?.pluginOnlySites ?? const [];
    showAppleModalSheet<void>(
      context: context,
      title: '选择片源 (${supported.length} 可用)',
      builder: (ctx) => ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        children: [
          if (directSites.isNotEmpty) ...[
            AppleGroupedSection(
              header: '直连 HTTP/HTTPS 片源 (${directSites.length})',
              children: directSites.map((s) {
                final selected = s.key == _activeSite?.key;
                return AppleGroupedTile(
                  icon: CupertinoIcons.tv_fill,
                  iconBgColor: selected
                      ? AppleColors.systemBlue
                      : AppleColors.groupedTertiary,
                  title: s.name,
                  subtitle: s.api,
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
                    setState(() => _activeSite = s);
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
                  subtitle: 'Apple 跨平台智能解析 · ${s.api}',
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
                    setState(() => _activeSite = s);
                    _loadSiteHome();
                  },
                );
              }).toList(),
            ),
          if (plugins.isNotEmpty) ...[
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
      ),
    );
  }
  void _showImportConfigDialog({
    String? initialInput,
    String? initialError,
  }) {
    final urlCtrl = TextEditingController(
      text: initialInput ?? widget.storage.configOrigin,
    );
    final jsonCtrl = TextEditingController();
    bool submitting = false;
    String? errorText = initialError;
    showAppleModalSheet<void>(
      context: context,
      title: '导入配置',
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
              final candidateBackup =
                  jsonText.isNotEmpty ? jsonText : urlText;
              if (CmsParser.isBackupPayload(candidateBackup)) {
                await widget.storage.importBackupJson(candidateBackup);
                if (ctx.mounted) Navigator.of(ctx).pop();
                _reloadSavedConfig();
                return;
              }
              final resolved = await widget.client.resolveConfigInput(
                urlInput: urlText,
                rawTextInput: jsonText,
              );
              await widget.storage.saveConfig(
                resolved.rawConfig,
                origin: resolved.origin,
                depots: resolved.depots,
                activeDepotName: resolved.activeDepotName,
              );
              if (ctx.mounted) Navigator.of(ctx).pop();
              _reloadSavedConfig();
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
                Row(
                  children: [
                    const Expanded(
                      child: Text(
                        '远程接口地址（支持单仓、多仓、中文域名）',
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
                        if (clip.startsWith('{') || clip.startsWith('[')) {
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
                  placeholder: 'https://config.example/config.json',
                  autocorrect: false,
                  enableSuggestions: false,
                  smartQuotesType: SmartQuotesType.disabled,
                  smartDashesType: SmartDashesType.disabled,
                  keyboardType: TextInputType.url,
                  clearButtonMode: OverlayVisibilityMode.editing,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 12,
                  ),
                  style: const TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 14,
                  ),
                  placeholderStyle: const TextStyle(
                    color: AppleColors.labelTertiary,
                    fontSize: 14,
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
                const SizedBox(height: 16),
                const Text(
                  '或直接粘贴 JSON / Base64 配置内容（选填）',
                  style: TextStyle(
                    color: AppleColors.labelSecondary,
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 8),
                CupertinoTextField(
                  controller: jsonCtrl,
                  placeholder: '{"sites": [...], "lives": [...]}',
                  maxLines: 5,
                  minLines: 3,
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
                    fontSize: 13,
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
                const SizedBox(height: 20),
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
                          label: '保存并启用配置',
                          icon: CupertinoIcons.checkmark_circle_fill,
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
