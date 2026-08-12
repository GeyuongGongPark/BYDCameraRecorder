import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'screens/server_list_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  runApp(const BydDashcamApp());
}

class BydDashcamApp extends StatelessWidget {
  const BydDashcamApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'BYD 블랙박스',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF3DC8FF),
          surface: Color(0xFF0D1926),
        ),
        scaffoldBackgroundColor: const Color(0xFF05080F),
        cardColor: const Color(0xFF0D1926),
        textTheme: const TextTheme(
          bodyMedium: TextStyle(color: Colors.white),
        ),
        useMaterial3: true,
      ),
      home: const ServerListScreen(),
    );
  }
}
