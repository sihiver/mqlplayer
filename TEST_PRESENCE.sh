#!/bin/bash

# MQLTV Presence Implementation - Testing Guide
# This script provides commands to test the presence feature

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║  MQLTV Presence Implementation - Testing Guide                 ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_test() {
    echo -e "${BLUE}→ Test $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

# Test 1: Check if PresenceManager is properly integrated
echo ""
print_test "1: Verify PresenceManager Integration"
echo "Checking files..."
if grep -q "PresenceManager" /home/dindin/AndroidStudioProjects/MQLTV/app/src/main/java/com/sihiver/mqltv/PlayerActivityExo.kt 2>/dev/null; then
    print_success "PresenceManager imported in PlayerActivityExo.kt"
else
    echo "⚠ PresenceManager not found in PlayerActivityExo.kt"
fi

if grep -q "presenceManager = PresenceManager" /home/dindin/AndroidStudioProjects/MQLTV/app/src/main/java/com/sihiver/mqltv/PlayerActivityExo.kt 2>/dev/null; then
    print_success "PresenceManager initialized in PlayerActivityExo.kt"
else
    echo "⚠ PresenceManager initialization not found"
fi

# Test 2: Check presence calls
echo ""
print_test "2: Verify Presence Method Calls"

echo "Checking sendOnlinePresence() calls..."
if grep -q "presenceManager.sendOnlinePresence" /home/dindin/AndroidStudioProjects/MQLTV/app/src/main/java/com/sihiver/mqltv/PlayerActivityExo.kt 2>/dev/null; then
    print_success "sendOnlinePresence() called in PlayerActivityExo"
else
    echo "⚠ sendOnlinePresence() not found in PlayerActivityExo"
fi

echo "Checking sendOfflinePresence() calls..."
if grep -q "presenceManager.sendOfflinePresence" /home/dindin/AndroidStudioProjects/MQLTV/app/src/main/java/com/sihiver/mqltv/PlayerActivityExo.kt 2>/dev/null; then
    print_success "sendOfflinePresence() called in PlayerActivityExo"
else
    echo "⚠ sendOfflinePresence() not found"
fi

echo "Checking heartbeat calls..."
if grep -q "presenceManager.startHeartbeat" /home/dindin/AndroidStudioProjects/MQLTV/app/src/main/java/com/sihiver/mqltv/PlayerActivityExo.kt 2>/dev/null; then
    print_success "startHeartbeat() called in PlayerActivityExo"
else
    echo "⚠ startHeartbeat() not found"
fi

# Test 3: Check VLC integration
echo ""
print_test "3: Verify VLC Player Integration"
if grep -q "PresenceManager" /home/dindin/AndroidStudioProjects/MQLTV/app/src/main/java/com/sihiver/mqltv/PlayerActivityVLC.kt 2>/dev/null; then
    print_success "PresenceManager integrated in PlayerActivityVLC.kt"
fi

# Test 4: Check Native player integration
echo ""
print_test "4: Verify Native Player Integration"
if grep -q "PresenceManager" /home/dindin/AndroidStudioProjects/MQLTV/app/src/main/java/com/sihiver/mqltv/PlayerActivityNative.kt 2>/dev/null; then
    print_success "PresenceManager integrated in PlayerActivityNative.kt"
fi

# Test 5: Build verification
echo ""
print_test "5: Verify Build Status"
print_info "Run: ./gradlew assembleDebug"
print_info "Expected: BUILD SUCCESSFUL"
echo ""
echo "Sample commands:"
echo "  cd /home/dindin/AndroidStudioProjects/MQLTV"
echo "  ./gradlew assembleDebug"
echo ""

# Test 6: Manual testing on device
echo ""
print_test "6: Manual Device Testing"
echo "Prerequisites:"
echo "  1. Device/emulator must have MQLTV app installed"
echo "  2. Backend server must be running (mql_manager)"
echo "  3. Device must have internet connectivity"
echo ""
print_info "Test Steps:"
echo ""
echo "  A. Test Online Status:"
echo "     1. Open MQLTV app"
echo "     2. Click on any channel"
echo "     3. Check backend: curl 'http://SERVER/api/presence'"
echo "     4. Verify user appears with status='online'"
echo ""
echo "  B. Test Heartbeat:"
echo "     1. Keep playback running"
echo "     2. Watch logs: adb logcat PresenceManager:D"
echo "     3. After 60 seconds, should see:"
echo "        'PresenceManager: Presence sent successfully (status=heartbeat)'"
echo "     4. Check backend: last_seen_at should update"
echo ""
echo "  C. Test Channel Switch:"
echo "     1. During playback, press UP/DOWN arrow"
echo "     2. Watch logs for new 'online' presence"
echo "     3. Backend should show new channel in presence"
echo ""
echo "  D. Test Offline:"
echo "     1. During playback, press BACK button"
echo "     2. Watch logs: should see 'sendOfflinePresence()' call"
echo "     3. Backend: user status should become 'offline'"
echo "     4. Heartbeat should stop"
echo ""

# Test 7: Log monitoring
echo ""
print_test "7: Monitor Presence Activity (Live)"
echo "Run these commands in different terminals:"
echo ""
echo "Terminal 1 - Build and run app:"
echo "  cd /home/dindin/AndroidStudioProjects/MQLTV"
echo "  ./gradlew installDebug"
echo "  # Then start app on device"
echo ""
echo "Terminal 2 - Watch presence logs:"
echo "  adb logcat PresenceManager:D"
echo ""
echo "Terminal 3 - Watch all player logs:"
echo "  adb logcat 'PlayerActivityExo:D|PlayerActivityVLC:D|PresenceManager:D'"
echo ""
echo "Terminal 4 - Monitor backend (if available):"
echo "  # Check presence endpoint regularly"
echo "  watch -n 5 'curl -s http://localhost:8080/api/presence | jq .'"
echo ""

# Test 8: API endpoint testing
echo ""
print_test "8: Test Backend API Directly"
echo "Assuming backend running at http://localhost:8080"
echo ""
echo "Command 1 - Send presence manually:"
echo ""
echo "  curl -X POST http://localhost:8080/public/presence \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{
    \"appKey\": \"test_app_key\",
    \"status\": \"online\",
    \"channelTitle\": \"Test Channel\",
    \"channelUrl\": \"http://test.com/stream.m3u8\"
  }'"
echo ""
echo "Expected response: { \"ok\": true }"
echo ""
echo "Command 2 - Check current presence:"
echo ""
echo "  curl http://localhost:8080/api/presence | jq ."
echo ""
echo "Expected response:"
echo "  {
    \"ok\": true,
    \"cutoff\": \"2026-05-03T...\",
    \"items\": [...]
  }"
echo ""

echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║  For detailed implementation docs, see:                        ║"
echo "║  PRESENCE_IMPLEMENTATION.md                                   ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
