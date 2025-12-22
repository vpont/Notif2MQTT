#!/usr/bin/env python3
"""
Script to receive Android notifications via MQTT and display them on Linux
"""

import paho.mqtt.client as mqtt
import json
import sys
import base64
import os
import configparser
from datetime import datetime
from pathlib import Path
import argparse
import socket
import gi

gi.require_version("Notify", "0.7")
from gi.repository import Notify, GdkPixbuf, GLib, Gio  # noqa: E402

# Global configuration
VERBOSE = True

# Runtime configuration (loaded from file or defaults)
config = {
    "broker": "localhost",
    "port": 1883,
    "ssl": False,
    "topic": "notif2mqtt/notifications",
    "username": "",
    "password": "",
}


# Colors
class Colors:
    HEADER = "\033[95m"
    BLUE = "\033[94m"
    CYAN = "\033[96m"
    GREEN = "\033[92m"
    WARNING = "\033[93m"
    FAIL = "\033[91m"
    ENDC = "\033[0m"
    BOLD = "\033[1m"
    UNDERLINE = "\033[4m"
    GREY = "\033[90m"


def get_config_path() -> Path:
    """Get the configuration file path following XDG Base Directory specification."""
    xdg_config_home = os.environ.get("XDG_CONFIG_HOME")
    if xdg_config_home:
        config_dir = Path(xdg_config_home) / "notif2mqtt"
    else:
        config_dir = Path.home() / ".config" / "notif2mqtt"
    return config_dir / "config.ini"


def load_config(config_file: Path = None):
    """Load configuration from file, falling back to defaults."""
    global config

    if config_file is None:
        config_file = get_config_path()

    if config_file.exists():
        try:
            parser = configparser.ConfigParser()
            parser.read(config_file)

            if "mqtt" in parser:
                mqtt_section = parser["mqtt"]
                config["broker"] = mqtt_section.get("broker", config["broker"])
                config["port"] = mqtt_section.getint("port", config["port"])
                config["ssl"] = mqtt_section.getboolean("ssl", config["ssl"])
                config["username"] = mqtt_section.get("username", config["username"])
                config["password"] = mqtt_section.get("password", config["password"])

            if VERBOSE:
                print(
                    f"{Colors.GREEN}✓ Loaded configuration from {config_file}{Colors.ENDC}"
                )
        except Exception as e:
            print(f"{Colors.FAIL}✗ Error reading config file: {e}{Colors.ENDC}")
            sys.exit(1)
    elif VERBOSE:
        print(
            f"{Colors.WARNING}⚠ No config file found at {config_file}, using defaults{Colors.ENDC}"
        )


def create_default_config(config_file: Path = None):
    """Create a default configuration file."""
    if config_file is None:
        config_file = get_config_path()

    config_file.parent.mkdir(parents=True, exist_ok=True)

    parser = configparser.ConfigParser()
    parser["mqtt"] = {
        "broker": config["broker"],
        "port": str(config["port"]),
        "ssl": str(config["ssl"]).lower(),
        "username": config["username"],
        "password": config["password"],
    }

    with open(config_file, "w") as f:
        parser.write(f)

    print(f"{Colors.GREEN}✓ Created default config at {config_file}{Colors.ENDC}")


def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print(
            f"{Colors.GREEN}✓ Connected to MQTT broker at {config['broker']}:{config['port']}{Colors.ENDC}"
        )
        client.subscribe(config["topic"])
        print(f"{Colors.GREEN}✓ Subscribed to topic: {config['topic']}{Colors.ENDC}")
    else:
        print(f"{Colors.FAIL}✗ Connection error. Code: {rc}{Colors.ENDC}")
        sys.exit(1)


def on_message(client, userdata, msg):
    try:
        # Parse JSON
        data = json.loads(msg.payload.decode())

        package = data.get("package", "unknown")
        app = data.get("app", "Unknown App")
        title = data.get("title", "Notification")
        text = data.get("text", "")
        timestamp = data.get("timestamp", 0)
        importance = data.get("importance", 3)
        urgency = data.get("urgency", "normal")
        category = data.get("category", "")
        icon_base64 = data.get("icon", None)

        # Console log
        if VERBOSE:
            # Determine urgency icon
            urgency_icon = {
                "high": "🔴",
                "normal": "🟢",
                "low": "🔵",
                "minimal": "⚪",
            }.get(urgency, "🟢")

            print(
                f"\n{urgency_icon} {Colors.BOLD}New notification from {Colors.CYAN}{app}{Colors.ENDC} [{urgency.upper()}]"
            )

            # Format timestamp
            try:
                dt_object = datetime.fromtimestamp(timestamp / 1000.0)
                formatted_time = dt_object.strftime("%Y-%m-%d %H:%M:%S")
            except Exception:
                formatted_time = "Unknown"

            print(f"   {Colors.BOLD}Title:{Colors.ENDC} {title}")
            print(f"   {Colors.BOLD}Text:{Colors.ENDC} {text}")
            print(f"   {Colors.BOLD}Category:{Colors.ENDC} {category}")
            print(
                f"   {Colors.BOLD}Time:{Colors.ENDC} {Colors.GREY}{formatted_time}{Colors.ENDC}"
            )
            print(f"   {Colors.BOLD}Package:{Colors.ENDC} {package}")
            print(
                f"   {Colors.BOLD}Urgency:{Colors.ENDC} {urgency} (importance: {importance})"
            )

        # Show notification on Linux using libnotify
        notification_title = f"{app}: {title}"

        try:
            # Create notification
            notification = Notify.Notification.new(notification_title, text, None)

            # Set urgency
            if urgency == "high":
                notification.set_urgency(Notify.Urgency.CRITICAL)
            elif urgency == "low" or urgency == "minimal":
                notification.set_urgency(Notify.Urgency.LOW)
            else:
                notification.set_urgency(Notify.Urgency.NORMAL)

            # Set category if available
            if category:
                notification.set_hint("category", GLib.Variant.new_string(category))

            # Process icon if available
            if icon_base64:
                try:
                    # Decode Base64
                    icon_data = base64.b64decode(icon_base64)

                    # Create memory stream from icon data
                    stream = Gio.MemoryInputStream.new_from_data(icon_data, None)

                    # Load icon into pixbuf from stream and set it
                    pixbuf = GdkPixbuf.Pixbuf.new_from_stream(stream, None)
                    notification.set_image_from_pixbuf(pixbuf)

                    if VERBOSE:
                        print("   Icon: Processed")
                except Exception as e:
                    if VERBOSE:
                        print(f"   Icon: Error processing ({e})")

            # Show notification
            notification.show()

        except Exception as e:
            print(f"{Colors.FAIL}✗ Error showing notification: {e}{Colors.ENDC}")

    except json.JSONDecodeError:
        print(
            f"{Colors.FAIL}✗ Error: Message is not valid JSON: {msg.payload}{Colors.ENDC}"
        )
    except Exception as e:
        print(f"{Colors.FAIL}✗ Error processing message: {e}{Colors.ENDC}")


def on_disconnect(client, userdata, rc):
    if rc != 0:
        print(f"{Colors.WARNING}⚠ Disconnected unexpectedly. Code: {rc}{Colors.ENDC}")


def main():
    global VERBOSE

    # Parse arguments
    parser = argparse.ArgumentParser(
        description="Receive Android notifications via MQTT"
    )
    parser.add_argument(
        "--daemon", action="store_true", help="Run in daemon mode (no console output)"
    )
    parser.add_argument("--config", "-c", type=Path, help="Path to configuration file")
    parser.add_argument(
        "--init-config",
        action="store_true",
        help="Create default configuration file and exit",
    )
    args = parser.parse_args()

    if args.daemon:
        VERBOSE = False

    # Handle --init-config
    if args.init_config:
        create_default_config(args.config)
        sys.exit(0)

    # Load configuration
    load_config(args.config)

    # Initialize libnotify
    if not Notify.init("Notif2MQTT"):
        print(f"{Colors.FAIL}✗ Error: Failed to initialize libnotify{Colors.ENDC}")
        sys.exit(1)

    print(
        f"{Colors.HEADER}🚀 Starting Android → Linux notification receiver{Colors.ENDC}"
    )
    print(f"   Broker: {config['broker']}:{config['port']}")
    if config["ssl"]:
        print("   Security: SSL/TLS enabled")
    else:
        print("   Security: Unencrypted connection")
    if args.daemon:
        print("   Mode: Daemon (Silent notifications)")
    print()

    # Create MQTT client
    client_id = socket.gethostname()
    client = mqtt.Client(client_id=client_id)

    # Configure callbacks
    client.on_connect = on_connect
    client.on_message = on_message
    client.on_disconnect = on_disconnect

    # Configure credentials if provided
    if config["username"] and config["password"]:
        client.username_pw_set(config["username"], config["password"])

    # Configure SSL if enabled
    if config["ssl"]:
        import ssl

        client.tls_set(cert_reqs=ssl.CERT_NONE)
        if VERBOSE:
            print(f"{Colors.BLUE}🔒 SSL/TLS enabled for secure connection{Colors.ENDC}")

    try:
        # Connect to broker
        client.connect(config["broker"], config["port"], 60)

        # Start loop
        if VERBOSE:
            print(
                f"{Colors.BLUE}⏳ Waiting for notifications... (Ctrl+C to exit){Colors.ENDC}\n"
            )
        client.loop_forever()

    except KeyboardInterrupt:
        print(f"\n\n{Colors.WARNING}👋 Stopping receiver...{Colors.ENDC}")
        client.disconnect()
        Notify.uninit()
        sys.exit(0)

    except Exception as e:
        print(f"\n{Colors.FAIL}✗ Error: {e}{Colors.ENDC}")
        Notify.uninit()
        sys.exit(1)


if __name__ == "__main__":
    main()
