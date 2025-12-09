#!/bin/bash

echo "=== MQLTV Debug Monitor ==="
echo "Monitoring untuk masalah channel click..."
echo "1. Buka aplikasi MQLTV di device"
echo "2. Coba klik salah satu channel"
echo "3. Lihat log di bawah untuk debugging"
echo ""
echo "Press Ctrl+C to stop monitoring"
echo "================================="

# Monitor log untuk MainActivity dan PlayerActivity
adb -s ZP22222BQ8 logcat | grep -E "(MainActivity|PlayerActivity|ChannelCard|MQLTV)"