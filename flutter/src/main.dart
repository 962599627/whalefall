import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';
import 'core/cms_client.dart';
import 'core/storage_service.dart';
import 'ui/adaptive_shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();
  final storage = await StorageService.init();
  final client = CmsClient();
  runApp(MarcoMovieApp(storage: storage, client: client));
}

class MarcoMovieApp extends StatelessWidget {
  final StorageService storage;
  final CmsClient client;

  const MarcoMovieApp({
    super.key,
    required this.storage,
    required this.client,
  });

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Marco Movie',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF101013),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF465C76),
          brightness: Brightness.dark,
          surface: const Color(0xFF18181E),
        ),
        useMaterial3: true,
      ),
      home: AdaptiveShell(storage: storage, client: client),
    );
  }
}
