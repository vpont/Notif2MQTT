#!/bin/bash
set -e

# Configuration
SERVICE_NAME="notif2mqtt.service"
SERVICE_FILE="notif2mqtt.service"
INSTALL_DIR="$HOME/.config/systemd/user"
BIN_DIR="$HOME/.local/bin"
SCRIPT_NAME="notif2mqtt"

echo "🚀 Installing Notif2MQTT Daemon..."

# Install dependencies
echo "📦 Installing Python dependencies..."
if command -v pacman &> /dev/null; then
    echo "   Detected pacman. Installing python-paho-mqtt..."
    sudo pacman -S --needed python-paho-mqtt
elif [ -f "requirements.txt" ]; then
    echo "   Installing via pip..."
    if command -v pip3 &> /dev/null; then
        pip3 install --user -r requirements.txt
    else
        python3 -m pip install --user -r requirements.txt
    fi
fi

# Create binary directory if it doesn't exist
mkdir -p "$BIN_DIR"

# Copy python script
echo "� Installing script to $BIN_DIR/$SCRIPT_NAME..."
cp linux_receiver.py "$BIN_DIR/$SCRIPT_NAME"
chmod +x "$BIN_DIR/$SCRIPT_NAME"

# Create systemd user directory if it doesn't exist
mkdir -p "$INSTALL_DIR"

# Copy service file
echo "📝 Copying service file to $INSTALL_DIR..."
cp "$SERVICE_FILE" "$INSTALL_DIR/"

# Reload systemd
echo "🔄 Reloading systemd..."
systemctl --user daemon-reload

# Enable and start service
echo "✅ Enabling and starting service..."
systemctl --user enable "$SERVICE_NAME"
systemctl --user restart "$SERVICE_NAME"

echo "🎉 Done! Service status:"
systemctl --user status "$SERVICE_NAME" --no-pager
