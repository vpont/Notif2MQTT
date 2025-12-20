#!/usr/bin/env python3
"""
Script to receive Android notifications via MQTT and display them on Linux
Requires: pip install paho-mqtt
"""

import paho.mqtt.client as mqtt
import json
import subprocess
import sys

# Configuration
MQTT_BROKER = "192.168.1.111"
MQTT_PORT = 1883
MQTT_TOPIC = "android/notifications"
MQTT_USERNAME = ""  # Leave empty if not required
MQTT_PASSWORD = ""  # Leave empty if not required

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print(f"✓ Connected to MQTT broker at {MQTT_BROKER}:{MQTT_PORT}")
        client.subscribe(MQTT_TOPIC)
        print(f"✓ Subscribed to topic: {MQTT_TOPIC}")
    else:
        print(f"✗ Connection error. Code: {rc}")
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
        priority = data.get('priority', 0)
        importance = data.get('importance', 3)
        urgency = data.get('urgency', 'normal')
        
        # Determine urgency icon
        urgency_icon = {
            'high': '🔴',
            'normal': '🟢',
            'low': '🔵',
            'minimal': '⚪'
        }.get(urgency, '🟢')
        
        # Console log
        print(f"\n{urgency_icon} New notification from {app} [{urgency.upper()}]")
        print(f"   Title: {title}")
        print(f"   Text: {text}")
        print(f"   Package: {package}")
        print(f"   Urgency: {urgency} (priority: {priority}, importance: {importance})")
        
        # Determine urgency for notify-send
        notify_urgency = 'normal'
        if urgency == 'high':
            notify_urgency = 'critical'
        elif urgency == 'low' or urgency == 'minimal':
            notify_urgency = 'low'
        
        # Show notification on Linux using notify-send
        notification_title = f"{app}: {title}"
        subprocess.run([
            'notify-send',
            notification_title,
            text,
            '-t', '5000',  # Duration in ms
            '-u', notify_urgency  # Urgency: low, normal, critical
        ], check=False)
        
    except json.JSONDecodeError:
        print(f"✗ Error: Message is not valid JSON: {msg.payload}")
    except Exception as e:
        print(f"✗ Error processing message: {e}")

def on_disconnect(client, userdata, rc):
    if rc != 0:
        print(f"⚠ Disconnected unexpectedly. Code: {rc}")

def main():
    print("🚀 Starting Android → Linux notification receiver")
    print(f"   Broker: {MQTT_BROKER}:{MQTT_PORT}")
    print(f"   Topic: {MQTT_TOPIC}")
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
        print("⏳ Waiting for notifications... (Ctrl+C to exit)\n")
        client.loop_forever()
        
    except KeyboardInterrupt:
        print("\n\n👋 Stopping receiver...")
        client.disconnect()
        sys.exit(0)
    except Exception as e:
        print(f"\n✗ Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
