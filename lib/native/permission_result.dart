/// Typed result returned by every permission request.
///
/// Using a sealed class instead of a raw bool forces callers to explicitly
/// handle the denied branch, preventing silent failures in the UI layer.
sealed class PermissionResult {
  const PermissionResult();
}

final class PermissionGranted extends PermissionResult {
  const PermissionGranted();
}

final class PermissionDenied extends PermissionResult {
  const PermissionDenied({required this.permission, this.reason});

  final String permission;
  final String? reason;

  @override
  String toString() =>
      'PermissionDenied(permission: $permission, reason: $reason)';
}
