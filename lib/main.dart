import 'package:flutter/material.dart';

import 'screens/permission_setup_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const OverlayMobileApp());
}

class OverlayMobileApp extends StatelessWidget {
  const OverlayMobileApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Overlay Mobile',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const PermissionSetupScreen(),
    );
  }
}
