#!/bin/bash
set -e

# Configuration
SERVICE_NAME="notif2mqtt.service"
SERVICE_FILE="notif2mqtt.service"
INSTALL_DIR="$HOME/.config/systemd/user"
BIN_DIR="$HOME/.local/bin"
SCRIPT_NAME="notif2mqtt"

echo "🗑️ Uninstalling Notif2MQTT Daemon..."

# Stop and disable service
echo "🛑 Stopping service..."
systemctl --user stop "$SERVICE_NAME" || true
systemctl --user disable "$SERVICE_NAME" || true

# Remove service file
if [ -f "$INSTALL_DIR/$SERVICE_FILE" ]; then
    echo "📄 Removing service file..."
    rm "$INSTALL_DIR/$SERVICE_FILE"
fi

# Remove python script
if [ -f "$BIN_DIR/$SCRIPT_NAME" ]; then
    echo "🗑️ Removing binary..."
    rm "$BIN_DIR/$SCRIPT_NAME"
fi

# Reload systemd
echo "🔄 Reloading systemd..."
systemctl --user daemon-reload

echo "✅ Uninstallation complete!"
