import 'package:flutter/material.dart';
import '../core/cms_client.dart';
import '../core/models.dart';
import '../core/storage_service.dart';
import 'detail_screen.dart';
import 'player_screen.dart';

/// Adaptive multi-platform shell: NavigationRail on Windows/macOS/iPad, NavigationBar on iPhone.
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

  // Search state
  final TextEditingController _searchController = TextEditingController();
  final List<(CmsSite, CmsVideo)> _searchResults = [];
  bool _searching = false;

  // Live state
  int _selectedLiveSource = 0;
  List<LiveGroup> _liveGroups = const [];
  bool _liveLoading = false;
  String? _liveError;

  @override
  void initState() {
    super.initState();
    _reloadSavedConfig();
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
    }
    if (saved != null && saved.lives.isNotEmpty) {
      _loadLivePlaylist(0);
    }
    setState(() {});
  }

  Future<void> _loadSiteHome() async {
    final site = _activeSite;
    if (site == null) return;
    setState(() {
      _homeLoading = true;
      _homeError = null;
      _selectedCategoryId = '';
      _currentPage = 1;
    });
    try {
      final (cats, listing) = await widget.client.fetchHome(site);
      if (!mounted || _activeSite?.key != site.key) return;
      setState(() {
        _categories = cats;
        _homeVideos = listing.videos;
        _currentPage = listing.page;
        _pageCount = listing.pageCount;
        _homeLoading = false;
      });
    } catch (e) {
      if (!mounted || _activeSite?.key != site.key) return;
      setState(() {
        _homeLoading = false;
        _homeError = '加载片单失败：$e';
      });
    }
  }

  Future<void> _loadCategory(String categoryId, {int page = 1}) async {
    final site = _activeSite;
    if (site == null) return;
    setState(() {
      _homeLoading = true;
      _homeError = null;
      _selectedCategoryId = categoryId;
    });
    try {
      final listing =
          await widget.client.fetchCategory(site, categoryId, page);
      if (!mounted) return;
      setState(() {
        _homeVideos = page == 1
            ? listing.videos
            : [..._homeVideos, ...listing.videos];
        _currentPage = listing.page;
        _pageCount = listing.pageCount;
        _homeLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _homeLoading = false;
        _homeError = '加载分类失败：$e';
      });
    }
  }

  Future<void> _runGlobalSearch(String keyword) async {
    final trimmed = keyword.trim();
    if (trimmed.isEmpty || _config == null) return;
    await widget.storage.addSearchKeyword(trimmed);
    setState(() {
      _searching = true;
      _searchResults.clear();
    });
    widget.client
        .searchAcrossSites(_config!.supportedSites, trimmed)
        .listen(
          (batch) {
            if (!mounted) return;
            final (site, videos) = batch;
            setState(() {
              for (final v in videos) {
                _searchResults.add((site, v));
              }
            });
          },
          onDone: () {
            if (mounted) setState(() => _searching = false);
          },
        );
  }

  Future<void> _loadLivePlaylist(int sourceIndex) async {
    final lives = _config?.lives ?? const [];
    if (sourceIndex < 0 || sourceIndex >= lives.length) return;
    setState(() {
      _selectedLiveSource = sourceIndex;
      _liveLoading = true;
      _liveError = null;
    });
    try {
      final groups = await widget.client.fetchLiveGroups(lives[sourceIndex]);
      if (!mounted) return;
      setState(() {
        _liveGroups = groups;
        _liveLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _liveLoading = false;
        _liveError = '读取直播列表失败：$e';
      });
    }
  }

  void _openVideoDetail(CmsSite site, CmsVideo video) {
    Navigator.of(context)
        .push(
          MaterialPageRoute<void>(
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

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final useSideRail = width >= 720;

    final pages = [
      _buildHomeTab(context),
      _buildSearchTab(context),
      _buildLiveTab(context),
      _buildFavoritesHistoryTab(context),
      _buildSettingsTab(context),
    ];

    return Scaffold(
      backgroundColor: const Color(0xFF101013),
      body: SafeArea(
        child: Row(
          children: [
            if (useSideRail)
              NavigationRail(
                backgroundColor: const Color(0xFF16161B),
                selectedIndex: _tabIndex,
                onDestinationSelected: (i) => setState(() => _tabIndex = i),
                labelType: NavigationRailLabelType.all,
                selectedIconTheme: const IconThemeData(color: Colors.white),
                unselectedIconTheme:
                    const IconThemeData(color: Color(0xFFA1A1AA)),
                selectedLabelTextStyle: const TextStyle(
                  color: Colors.white,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
                unselectedLabelTextStyle: const TextStyle(
                  color: Color(0xFFA1A1AA),
                  fontSize: 12,
                ),
                leading: const Padding(
                  padding: EdgeInsets.symmetric(vertical: 16),
                  child: Icon(
                    Icons.play_circle_fill_rounded,
                    color: Colors.white,
                    size: 32,
                  ),
                ),
                destinations: const [
                  NavigationRailDestination(
                    icon: Icon(Icons.home_outlined),
                    selectedIcon: Icon(Icons.home_rounded),
                    label: Text('首页'),
                  ),
                  NavigationRailDestination(
                    icon: Icon(Icons.search_rounded),
                    label: Text('搜索'),
                  ),
                  NavigationRailDestination(
                    icon: Icon(Icons.live_tv_rounded),
                    label: Text('直播'),
                  ),
                  NavigationRailDestination(
                    icon: Icon(Icons.bookmark_border_rounded),
                    selectedIcon: Icon(Icons.bookmark_rounded),
                    label: Text('收藏'),
                  ),
                  NavigationRailDestination(
                    icon: Icon(Icons.settings_outlined),
                    selectedIcon: Icon(Icons.settings_rounded),
                    label: Text('设置'),
                  ),
                ],
              ),
            Expanded(child: pages[_tabIndex]),
          ],
        ),
      ),
      bottomNavigationBar: useSideRail
          ? null
          : NavigationBar(
              backgroundColor: const Color(0xFF18181E),
              selectedIndex: _tabIndex,
              onDestinationSelected: (i) => setState(() => _tabIndex = i),
              destinations: const [
                NavigationDestination(
                  icon: Icon(Icons.home_outlined),
                  selectedIcon: Icon(Icons.home_rounded),
                  label: '首页',
                ),
                NavigationDestination(
                  icon: Icon(Icons.search_rounded),
                  label: '搜索',
                ),
                NavigationDestination(
                  icon: Icon(Icons.live_tv_rounded),
                  label: '直播',
                ),
                NavigationDestination(
                  icon: Icon(Icons.bookmark_border_rounded),
                  selectedIcon: Icon(Icons.bookmark_rounded),
                  label: '收藏',
                ),
                NavigationDestination(
                  icon: Icon(Icons.settings_outlined),
                  selectedIcon: Icon(Icons.settings_rounded),
                  label: '设置',
                ),
              ],
            ),
    );
  }

  Widget _buildHomeTab(BuildContext context) {
    if (_config == null || _activeSite == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'YOUR OWN CINEMA',
                  style: TextStyle(
                    color: Color(0xFFA9C9F3),
                    fontSize: 12,
                    letterSpacing: 1.8,
                  ),
                ),
                const SizedBox(height: 10),
                const Text(
                  '把今晚，留给好故事。',
                  style: TextStyle(
                    color: Color(0xFFF5F5F7),
                    fontSize: 32,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 14),
                const Text(
                  '属于你的全平台播放器（Windows · Mac · iPhone · iPad · Android）。导入自己合法获取的配置，即可在多端同步浏览和播放。',
                  style: TextStyle(
                    color: Color(0xFFA1A1AA),
                    fontSize: 15,
                    height: 1.5,
                  ),
                ),
                const SizedBox(height: 24),
                Wrap(
                  spacing: 12,
                  runSpacing: 10,
                  children: [
                    FilledButton.icon(
                      style: FilledButton.styleFrom(
                        backgroundColor: const Color(0xFFF5F5F7),
                        foregroundColor: const Color(0xFF101013),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 22,
                          vertical: 14,
                        ),
                      ),
                      onPressed: _showImportConfigDialog,
                      icon: const Icon(Icons.add),
                      label: const Text(
                        '导入我的配置',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                    ),
                    OutlinedButton(
                      style: OutlinedButton.styleFrom(
                        foregroundColor: Colors.white,
                        side: const BorderSide(color: Colors.white24),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 14,
                        ),
                      ),
                      onPressed: _showBackupRestoreDialog,
                      child: const Text('从备份恢复'),
                    ),
                  ],
                ),
                const SizedBox(height: 28),
                const Text(
                  '本产品仅为播放工具，不内置、不提供、不托管任何影视资源或配置地址。请自行合法获取内容与配置，并确保拥有相应使用权。',
                  style: TextStyle(color: Color(0xFF71717A), fontSize: 12),
                ),
              ],
            ),
          ),
        ),
      );
    }

    final history = widget.storage.getWatchHistory();
    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Text(
                      'MARCO MOVIE',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.4,
                      ),
                    ),
                    const Spacer(),
                    ActionChip(
                      backgroundColor: const Color(0xFF242429),
                      label: Text(
                        '当前源：${_activeSite!.name} ▾',
                        style: const TextStyle(color: Colors.white),
                      ),
                      onPressed: _showSitePickerDialog,
                    ),
                  ],
                ),
                if (history.isNotEmpty) ...[
                  const SizedBox(height: 18),
                  const Text(
                    '继续播放',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 17,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 10),
                  SizedBox(
                    height: 76,
                    child: ListView.separated(
                      scrollDirection: Axis.horizontal,
                      itemCount: history.length.clamp(0, 6),
                      separatorBuilder: (_, __) => const SizedBox(width: 12),
                      itemBuilder: (ctx, i) {
                        final entry = history[i];
                        return InkWell(
                          borderRadius: BorderRadius.circular(14),
                          onTap: () {
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
                          child: Container(
                            width: 240,
                            padding: const EdgeInsets.all(10),
                            decoration: BoxDecoration(
                              color: const Color(0xFF222228),
                              borderRadius: BorderRadius.circular(14),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Text(
                                  entry.title,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    color: Colors.white,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  entry.episode,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    color: Color(0xFFA1A1AA),
                                    fontSize: 12,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                ],
                const SizedBox(height: 18),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    ChoiceChip(
                      label: const Text('推荐'),
                      selected: _selectedCategoryId.isEmpty,
                      selectedColor: const Color(0xFF465C76),
                      backgroundColor: const Color(0xFF242429),
                      labelStyle: const TextStyle(color: Colors.white),
                      onSelected: (_) => _loadSiteHome(),
                    ),
                    ..._categories.take(10).map(
                          (cat) => ChoiceChip(
                            label: Text(cat.name),
                            selected: _selectedCategoryId == cat.id,
                            selectedColor: const Color(0xFF465C76),
                            backgroundColor: const Color(0xFF242429),
                            labelStyle: const TextStyle(color: Colors.white),
                            onSelected: (_) => _loadCategory(cat.id),
                          ),
                        ),
                  ],
                ),
              ],
            ),
          ),
        ),
        if (_homeError != null)
          SliverFillRemaining(
            child: Center(
              child: Text(
                _homeError!,
                style: const TextStyle(color: Colors.redAccent),
              ),
            ),
          )
        else
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
            sliver: SliverGrid(
              gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                maxCrossAxisExtent: 180,
                mainAxisSpacing: 16,
                crossAxisSpacing: 14,
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
            padding: const EdgeInsets.symmetric(vertical: 20),
            child: Center(
              child: _homeLoading
                  ? const CircularProgressIndicator()
                  : (_currentPage < _pageCount
                      ? OutlinedButton(
                          onPressed: () => _loadCategory(
                            _selectedCategoryId,
                            page: _currentPage + 1,
                          ),
                          child: const Text('加载更多'),
                        )
                      : const SizedBox.shrink()),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildVideoCard(CmsSite site, CmsVideo video, {String? badge}) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () => _openVideoDetail(site, video),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: Stack(
                fit: StackFit.expand,
                children: [
                  video.poster.isNotEmpty
                      ? Image.network(
                          video.poster,
                          fit: BoxFit.cover,
                          errorBuilder: (_, __, ___) => Container(
                            color: const Color(0xFF1E293B),
                            child: const Icon(
                              Icons.movie_outlined,
                              color: Colors.white38,
                            ),
                          ),
                        )
                      : Container(
                          color: const Color(0xFF1E293B),
                          child: const Icon(
                            Icons.movie_outlined,
                            color: Colors.white38,
                          ),
                        ),
                  if (badge != null && badge.isNotEmpty)
                    Positioned(
                      top: 6,
                      left: 6,
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 6,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.black87,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          badge,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 10,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            video.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: Color(0xFFF5F5F7),
              fontSize: 14,
              fontWeight: FontWeight.w500,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            video.remarks,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: Color(0xFFA1A1AA), fontSize: 11),
          ),
        ],
      ),
    );
  }

  Widget _buildSearchTab(BuildContext context) {
    final history = widget.storage.getSearchHistory();
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '全局聚合搜索',
            style: TextStyle(
              color: Colors.white,
              fontSize: 26,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _searchController,
                  style: const TextStyle(color: Colors.white),
                  decoration: InputDecoration(
                    hintText: '输入片名、演员或关键词回车搜索…',
                    hintStyle: const TextStyle(color: Color(0xFF71717A)),
                    filled: true,
                    fillColor: const Color(0xFF1C1C22),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(14),
                      borderSide: BorderSide.none,
                    ),
                  ),
                  onSubmitted: _runGlobalSearch,
                ),
              ),
              const SizedBox(width: 12),
              FilledButton(
                onPressed: () => _runGlobalSearch(_searchController.text),
                child: const Text('搜索'),
              ),
            ],
          ),
          if (history.isNotEmpty) ...[
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: history
                  .map(
                    (kw) => ActionChip(
                      label: Text(kw),
                      backgroundColor: const Color(0xFF242429),
                      labelStyle: const TextStyle(color: Colors.white70),
                      onPressed: () {
                        _searchController.text = kw;
                        _runGlobalSearch(kw);
                      },
                    ),
                  )
                  .toList(),
            ),
          ],
          const SizedBox(height: 16),
          if (_searching) const LinearProgressIndicator(),
          Expanded(
            child: _searchResults.isEmpty && !_searching
                ? const Center(
                    child: Text(
                      '并发检索所有已启用片源，点击任意结果即可查看详情与选集。',
                      style: TextStyle(color: Color(0xFFA1A1AA)),
                    ),
                  )
                : GridView.builder(
                    gridDelegate:
                        const SliverGridDelegateWithMaxCrossAxisExtent(
                      maxCrossAxisExtent: 180,
                      mainAxisSpacing: 16,
                      crossAxisSpacing: 14,
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
    );
  }

  Widget _buildLiveTab(BuildContext context) {
    final lives = _config?.lives ?? const [];
    if (lives.isEmpty) {
      return const Center(
        child: Text(
          '当前配置没有包含 M3U / TXT 直播源。',
          style: TextStyle(color: Color(0xFFA1A1AA)),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Text(
                '电视直播',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 26,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Wrap(
                  spacing: 8,
                  children: List.generate(lives.length, (i) {
                    return ChoiceChip(
                      label: Text(lives[i].name),
                      selected: i == _selectedLiveSource,
                      onSelected: (_) => _loadLivePlaylist(i),
                    );
                  }),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          if (_liveLoading)
            const Expanded(child: Center(child: CircularProgressIndicator()))
          else if (_liveError != null)
            Expanded(
              child: Center(
                child: Text(
                  _liveError!,
                  style: const TextStyle(color: Colors.redAccent),
                ),
              ),
            )
          else
            Expanded(
              child: ListView.builder(
                itemCount: _liveGroups.length,
                itemBuilder: (ctx, gIdx) {
                  final group = _liveGroups[gIdx];
                  return ExpansionTile(
                    initiallyExpanded: gIdx == 0,
                    title: Text(
                      '${group.name} (${group.channels.length})',
                      style: const TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    children: group.channels.map((ch) {
                      return ListTile(
                        leading: const Icon(
                          Icons.tv_rounded,
                          color: Color(0xFFA9C9F3),
                        ),
                        title: Text(
                          ch.name,
                          style: const TextStyle(color: Colors.white),
                        ),
                        subtitle: Text(
                          '${ch.urls.length} 条线路',
                          style: const TextStyle(
                            color: Color(0xFFA1A1AA),
                            fontSize: 12,
                          ),
                        ),
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
                            MaterialPageRoute<void>(
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
    );
  }

  Widget _buildFavoritesHistoryTab(BuildContext context) {
    final favorites = widget.storage.getFavorites();
    final history = widget.storage.getWatchHistory();
    return DefaultTabController(
      length: 2,
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const TabBar(
              isScrollable: true,
              tabs: [
                Tab(text: '我的收藏'),
                Tab(text: '观看历史'),
              ],
            ),
            const SizedBox(height: 16),
            Expanded(
              child: TabBarView(
                children: [
                  favorites.isEmpty
                      ? const Center(
                          child: Text(
                            '还没有收藏任何影片。',
                            style: TextStyle(color: Color(0xFFA1A1AA)),
                          ),
                        )
                      : GridView.builder(
                          gridDelegate:
                              const SliverGridDelegateWithMaxCrossAxisExtent(
                            maxCrossAxisExtent: 180,
                            mainAxisSpacing: 16,
                            crossAxisSpacing: 14,
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
                        ),
                  history.isEmpty
                      ? const Center(
                          child: Text(
                            '暂无观看历史。',
                            style: TextStyle(color: Color(0xFFA1A1AA)),
                          ),
                        )
                      : ListView.builder(
                          itemCount: history.length,
                          itemBuilder: (ctx, i) {
                            final h = history[i];
                            return ListTile(
                              title: Text(
                                h.title,
                                style: const TextStyle(color: Colors.white),
                              ),
                              subtitle: Text(
                                '${h.episode} · ${h.line}',
                                style:
                                    const TextStyle(color: Color(0xFFA1A1AA)),
                              ),
                              onTap: () {
                                if (_config == null) return;
                                final site = _config!.supportedSites.firstWhere(
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
                          },
                        ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSettingsTab(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        const Text(
          '设置与跨端同步',
          style: TextStyle(
            color: Colors.white,
            fontSize: 26,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 18),
        ListTile(
          tileColor: const Color(0xFF1C1C22),
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          leading: const Icon(Icons.link_rounded, color: Colors.white),
          title: const Text('导入或更新配置', style: TextStyle(color: Colors.white)),
          subtitle: Text(
            widget.storage.configOrigin.isEmpty
                ? '支持 HTTP(S) 配置地址或直接粘贴 JSON 配置'
                : '当前来源：${widget.storage.configOrigin}',
            style: const TextStyle(color: Color(0xFFA1A1AA)),
          ),
          onTap: _showImportConfigDialog,
        ),
        const SizedBox(height: 12),
        ListTile(
          tileColor: const Color(0xFF1C1C22),
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          leading: const Icon(Icons.sync_alt_rounded, color: Colors.white),
          title: const Text(
            '与安卓电视 / 手机互通备份',
            style: TextStyle(color: Colors.white),
          ),
          subtitle: const Text(
            '导出或恢复 marco-movie-backup JSON（含配置、收藏、观看历史）',
            style: TextStyle(color: Color(0xFFA1A1AA)),
          ),
          onTap: _showBackupRestoreDialog,
        ),
        const SizedBox(height: 12),
        SwitchListTile(
          tileColor: const Color(0xFF1C1C22),
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          title: const Text('自动播放下一集', style: TextStyle(color: Colors.white)),
          value: widget.storage.autoNext,
          onChanged: (v) async {
            await widget.storage.setAutoNext(v);
            setState(() {});
          },
        ),
        const SizedBox(height: 24),
        const Text(
          '关于 Marco Movie (0.4.15 Multiplatform)\n'
          '本产品仅为播放工具，不内置、不提供、不托管任何影视资源或配置地址。',
          style: TextStyle(color: Color(0xFF71717A), fontSize: 12, height: 1.5),
        ),
      ],
    );
  }

  void _showSitePickerDialog() {
    final sites = _config?.supportedSites ?? const [];
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF1C1C22),
        title: const Text('切换片源', style: TextStyle(color: Colors.white)),
        content: SizedBox(
          width: 360,
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: sites.length,
            itemBuilder: (ctx, i) {
              final s = sites[i];
              return ListTile(
                title: Text(s.name, style: const TextStyle(color: Colors.white)),
                trailing: s.key == _activeSite?.key
                    ? const Icon(Icons.check, color: Colors.white)
                    : null,
                onTap: () {
                  Navigator.pop(ctx);
                  widget.storage.setSelectedSiteKey(s.key);
                  setState(() => _activeSite = s);
                  _loadSiteHome();
                },
              );
            },
          ),
        ),
      ),
    );
  }

  void _showImportConfigDialog() {
    final urlCtrl = TextEditingController(text: widget.storage.configOrigin);
    final jsonCtrl = TextEditingController();
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF1C1C22),
        title: const Text('导入配置', style: TextStyle(color: Colors.white)),
        content: SizedBox(
          width: 440,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: urlCtrl,
                style: const TextStyle(color: Colors.white),
                decoration: const InputDecoration(
                  labelText: '远程配置地址 HTTP(S)',
                  hintText: 'https://config.example/config.json',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: jsonCtrl,
                maxLines: 4,
                style: const TextStyle(color: Colors.white),
                decoration: const InputDecoration(
                  labelText: '或直接粘贴 JSON 配置内容',
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () async {
              try {
                String raw = jsonCtrl.text.trim();
                final origin = urlCtrl.text.trim();
                if (raw.isEmpty && origin.isNotEmpty) {
                  raw = await widget.client.fetchRawConfig(origin);
                }
                await widget.storage.saveConfig(raw, origin: origin);
                if (ctx.mounted) Navigator.pop(ctx);
                _reloadSavedConfig();
              } catch (e) {
                if (ctx.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(content: Text('导入失败：$e')),
                  );
                }
              }
            },
            child: const Text('保存并启用'),
          ),
        ],
      ),
    );
  }

  void _showBackupRestoreDialog() {
    final ctrl = TextEditingController(text: widget.storage.exportBackupJson());
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF1C1C22),
        title: const Text(
          '跨平台备份导出 / 恢复',
          style: TextStyle(color: Colors.white),
        ),
        content: SizedBox(
          width: 480,
          child: TextField(
            controller: ctrl,
            maxLines: 10,
            style: const TextStyle(color: Colors.white, fontSize: 12),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('关闭'),
          ),
          FilledButton(
            onPressed: () async {
              try {
                await widget.storage.importBackupJson(ctrl.text);
                if (ctx.mounted) Navigator.pop(ctx);
                _reloadSavedConfig();
              } catch (e) {
                if (ctx.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(content: Text('恢复备份失败：$e')),
                  );
                }
              }
            },
            child: const Text('恢复此备份'),
          ),
        ],
      ),
    );
  }
}
