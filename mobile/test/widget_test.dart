import 'package:flutter_test/flutter_test.dart';
import 'package:byd_dashcam/main.dart';

void main() {
  testWidgets('App smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const BydDashcamApp());
    expect(find.text('BYD 블랙박스'), findsOneWidget);
  });
}
