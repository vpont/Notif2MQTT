#!/usr/bin/env python3
"""
Script to receive Android notifications via MQTT and display them on Linux
Requires: pip install paho-mqtt
"""

import paho.mqtt.client as mqtt
import json
import subprocess
import sys
import base64
import os
import tempfile
from datetime import datetime
import argparse

# Global configuration
VERBOSE = True

# Configuration
MQTT_BROKER = "192.168.1.111"
MQTT_PORT = 1883
MQTT_TOPIC = "notif2mqtt/notifications"
MQTT_USERNAME = ""  # Leave empty if not required
MQTT_PASSWORD = ""  # Leave empty if not required

# Colors
class Colors:
    HEADER = '\033[95m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    GREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'
    UNDERLINE = '\033[4m'
    GREY = '\033[90m'

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print(f"{Colors.GREEN}✓ Connected to MQTT broker at {MQTT_BROKER}:{MQTT_PORT}{Colors.ENDC}")
        client.subscribe(MQTT_TOPIC)
        print(f"{Colors.GREEN}✓ Subscribed to topic: {MQTT_TOPIC}{Colors.ENDC}")
    else:
        print(f"{Colors.FAIL}✗ Connection error. Code: {rc}{Colors.ENDC}")
        sys.exit(1)

def on_message(client, userdata, msg):
    try:
        # Parse JSON
        data = json.loads(msg.payload.decode())

        package = data.get('package', 'unknown')
        app = data.get('app', 'Unknown App')
        title = data.get('title', 'Notification')
        text = data.get('text', '')
        timestamp = data.get('timestamp', 0)
        importance = data.get('importance', 3)
        urgency = data.get('urgency', 'normal')
        category = data.get('category', '')
        icon_base64 = data.get('icon', None)

        # Determine urgency icon
        urgency_icon = {
            'high': '🔴',
            'normal': '🟢',
            'low': '🔵',
            'minimal': '⚪'
        }.get(urgency, '🟢')

        # Console log
        if VERBOSE:
            print(f"\n{urgency_icon} {Colors.BOLD}New notification from {Colors.CYAN}{app}{Colors.ENDC} [{urgency.upper()}]")

            # Format timestamp
            try:
                dt_object = datetime.fromtimestamp(timestamp / 1000.0)
                formatted_time = dt_object.strftime('%Y-%m-%d %H:%M:%S')
            except:
                formatted_time = "Unknown"

            print(f"   {Colors.BOLD}Title:{Colors.ENDC} {title}")
            print(f"   {Colors.BOLD}Text:{Colors.ENDC} {text}")
            print(f"   {Colors.BOLD}Category:{Colors.ENDC} {category}")
            print(f"   {Colors.BOLD}Time:{Colors.ENDC} {Colors.GREY}{formatted_time}{Colors.ENDC}")
            print(f"   {Colors.BOLD}Package:{Colors.ENDC} {package}")
            print(f"   {Colors.BOLD}Urgency:{Colors.ENDC} {urgency} (importance: {importance})")

        # Determine urgency for notify-send
        notify_urgency = 'normal'
        if urgency == 'high':
            notify_urgency = 'critical'
        elif urgency == 'low' or urgency == 'minimal':
            notify_urgency = 'low'

        # Show notification on Linux using notify-send
        notification_title = f"{app}: {title}"

        # Prepare notify-send arguments
        notify_args = [
            'notify-send',
            notification_title,
            text,
            '-t', '5000',  # Duration in ms
            '-u', notify_urgency  # Urgency: low, normal, critical
        ]

        # Process icon if available
        icon_path = None
        if icon_base64:
            try:
                # Decode Base64
                icon_data = base64.b64decode(icon_base64)

                # Create temp file
                fd, icon_path = tempfile.mkstemp(suffix='.png')
                with os.fdopen(fd, 'wb') as f:
                    f.write(icon_data)

                # Add icon to arguments
                notify_args.extend(['-i', icon_path])
                if VERBOSE:
                    print(f"   Icon: Processed (saved to {icon_path})")
            except Exception as e:
                if VERBOSE:
                    print(f"   Icon: Error processing ({e})")

        # Execute notify-send
        subprocess.run(notify_args, check=False)

        # Cleanup temp file
        if icon_path and os.path.exists(icon_path):
            try:
                os.remove(icon_path)
            except:
                pass

    except json.JSONDecodeError:
        print(f"{Colors.FAIL}✗ Error: Message is not valid JSON: {msg.payload}{Colors.ENDC}")
    except Exception as e:
        print(f"{Colors.FAIL}✗ Error processing message: {e}{Colors.ENDC}")

def on_disconnect(client, userdata, rc):
    if rc != 0:
        print(f"{Colors.WARNING}⚠ Disconnected unexpectedly. Code: {rc}{Colors.ENDC}")

def main():
    global VERBOSE

    # Parse arguments
    parser = argparse.ArgumentParser(description='Receive Android notifications via MQTT')
    parser.add_argument('--daemon', action='store_true', help='Run in daemon mode (no console output)')
    args = parser.parse_args()

    if args.daemon:
        VERBOSE = False

    print(f"{Colors.HEADER}🚀 Starting Android → Linux notification receiver{Colors.ENDC}")
    print(f"   Broker: {MQTT_BROKER}:{MQTT_PORT}")
    print(f"   Topic: {MQTT_TOPIC}")
    if args.daemon:
        print(f"   Mode: Daemon (Silent notifications)")
    print()

    # Create MQTT client
    client = mqtt.Client(client_id="linux_notification_receiver")

    # Configure callbacks
    client.on_connect = on_connect
    client.on_message = on_message
    client.on_disconnect = on_disconnect

    # Configure credentials if provided
    if MQTT_USERNAME and MQTT_PASSWORD:
        client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)

    try:
        # Connect to broker
        client.connect(MQTT_BROKER, MQTT_PORT, 60)

        # Start loop
        print(f"{Colors.BLUE}⏳ Waiting for notifications... (Ctrl+C to exit){Colors.ENDC}\n")
        client.loop_forever()

    except KeyboardInterrupt:
        print(f"\n\n{Colors.WARNING}👋 Stopping receiver...{Colors.ENDC}")
        client.disconnect()
        sys.exit(0)

    except Exception as e:
        print(f"\n{Colors.FAIL}✗ Error: {e}{Colors.ENDC}")
        sys.exit(1)

if __name__ == "__main__":
    main()
