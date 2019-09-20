#!/bin/bash

cd /home/lee
sleep 20

echo "bluetooth: initializing.."

iw dev wlan0 set power_save off

# needed for backward compatibility
bluetootchctl add SP

bluetoothctl select DC:71:96:1C:90:F7
bluetoothctl power on
bluetoothctl power on
