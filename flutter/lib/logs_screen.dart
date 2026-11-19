import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import 'ember_ui.dart';

class LogsScreen extends StatefulWidget {
  const LogsScreen({super.key, required this.ui});

  final EmberUi ui;

  @override
  State<LogsScreen> createState() => _LogsScreenState();
}

class _LogsScreenState extends State<LogsScreen> {
  late Future<String> _logs;

  @override
  void initState() {
    super.initState();
    _logs = widget.ui.api.getLogs();
  }

  void _refresh() {
    setState(() => _logs = widget.ui.api.getLogs());
  }

  Future<void> _copy() async {
    final text = await widget.ui.run(() => widget.ui.api.getLogs());
    if (text == null) return;
    await Clipboard.setData(ClipboardData(text: text));
    widget.ui.tell(widget.ui.strings.get('copied'));
  }

  @override
  Widget build(BuildContext context) {
    final copy = widget.ui.strings;
    return ListView(
      key: const PageStorageKey('logs'),
      padding: const EdgeInsets.fromLTRB(4, 4, 4, 24),
      children: [
        EmberPageHeader(
          copy.get('logs'),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              IconButton(
                tooltip: copy.get('refresh'),
                onPressed: _refresh,
                icon: const SolarIcon('refresh'),
              ),
              IconButton(
                tooltip: copy.get('copy'),
                onPressed: _copy,
                icon: const SolarIcon('copy'),
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        EmberCard(
          padding: const EdgeInsets.all(16),
          child: SizedBox(
            height: MediaQuery.sizeOf(context).height * .58,
            width: double.infinity,
            child: FutureBuilder<String>(
              future: _logs,
              builder: (context, snapshot) {
                if (snapshot.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snapshot.hasError) {
                  return Center(child: Text(copy.get('logsUnavailable')));
                }
                final text = snapshot.data?.trim() ?? '';
                if (text.isEmpty) {
                  return Center(child: Text(copy.get('noLogs')));
                }
                return SingleChildScrollView(
                  child: SelectableText(
                    text,
                    style: Theme.of(context).textTheme.bodySmall
                        ?.copyWith(fontFamily: 'monospace'),
                  ),
                );
              },
            ),
          ),
        ),
      ],
    );
  }
}
