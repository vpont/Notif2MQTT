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
import io
import gi

gi.require_version("Notify", "0.7")
from gi.repository import Notify, GdkPixbuf, GLib, Gio  # noqa: E402

try:
    from PIL import Image, ImageDraw
    PIL_AVAILABLE = True
except ImportError:
    PIL_AVAILABLE = False
    print("Warning: Pillow not installed. Composite images (icon + preview) won't work.")
    print("Install with: pip install Pillow")

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


def pil_to_pixbuf(pil_image):
    """Convert PIL Image to GdkPixbuf.Pixbuf"""
    buffer = io.BytesIO()
    pil_image.save(buffer, format='PNG')
    buffer.seek(0)
    stream = Gio.MemoryInputStream.new_from_data(buffer.read(), None)
    pixbuf = GdkPixbuf.Pixbuf.new_from_stream(stream, None)
    return pixbuf


def pixbuf_to_pil(pixbuf):
    """Convert GdkPixbuf.Pixbuf to PIL Image"""
    width = pixbuf.get_width()
    height = pixbuf.get_height()
    rowstride = pixbuf.get_rowstride()
    pixels = pixbuf.get_pixels()
    has_alpha = pixbuf.get_has_alpha()

    if has_alpha:
        mode = 'RGBA'
    else:
        mode = 'RGB'

    image = Image.frombytes(mode, (width, height), pixels, 'raw', mode, rowstride)
    return image


def create_composite_image(icon_base64, preview_base64, position='bottom-right'):
    """
    Create composite image with icon overlaid on preview

    Args:
        icon_base64: Base64 encoded icon (128x128 PNG)
        preview_base64: Base64 encoded preview (256x256 JPEG)
        position: Icon position ('bottom-right', 'top-right', 'top-left', 'bottom-left')

    Returns:
        GdkPixbuf.Pixbuf of composite image, or None on error
    """
    if not PIL_AVAILABLE:
        return None

    try:
        # Decode images
        icon_data = base64.b64decode(icon_base64)
        preview_data = base64.b64decode(preview_base64)

        # Load as PIL Images
        icon_img = Image.open(io.BytesIO(icon_data)).convert('RGBA')
        preview_img = Image.open(io.BytesIO(preview_data)).convert('RGBA')

        # Create composite
        composite = preview_img.copy()

        # Resize icon to 64x64 for overlay
        icon_size = (64, 64)
        icon_resized = icon_img.resize(icon_size, Image.Resampling.LANCZOS)

        # Calculate position
        margin = 8
        if position == 'bottom-right':
            x = composite.width - icon_size[0] - margin
            y = composite.height - icon_size[1] - margin
        elif position == 'top-right':
            x = composite.width - icon_size[0] - margin
            y = margin
        elif position == 'top-left':
            x = margin
            y = margin
        elif position == 'bottom-left':
            x = margin
            y = composite.height - icon_size[1] - margin
        else:
            x = margin
            y = margin

        # Add subtle shadow for visibility
        shadow = Image.new('RGBA', icon_size, (0, 0, 0, 0))
        shadow_draw = ImageDraw.Draw(shadow)
        shadow_draw.ellipse([4, 4, icon_size[0]-4, icon_size[1]-4], fill=(0, 0, 0, 100))
        composite.paste(shadow, (x+2, y+2), shadow)

        # Paste icon using alpha channel as mask
        composite.paste(icon_resized, (x, y), icon_resized)

        # Convert back to pixbuf
        return pil_to_pixbuf(composite)

    except Exception as e:
        if VERBOSE:
            print(f"   Error creating composite image: {e}")
        return None


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
        preview_image_base64 = data.get("previewImage", None)

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
            if category:
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

            # Process images: Create composite if both available, otherwise show what we have
            if preview_image_base64 and icon_base64:
                # Both preview and icon available: create composite image
                if VERBOSE:
                    print("   Creating composite image (preview + icon)...")

                composite_pixbuf = create_composite_image(icon_base64, preview_image_base64, position='bottom-right')

                if composite_pixbuf:
                    try:
                        # Use set_hint with image-data for composite
                        notification.set_hint(
                            "image-data",
                            GLib.Variant(
                                "(iiibiiay)",
                                (
                                    composite_pixbuf.get_width(),
                                    composite_pixbuf.get_height(),
                                    composite_pixbuf.get_rowstride(),
                                    composite_pixbuf.get_has_alpha(),
                                    composite_pixbuf.get_bits_per_sample(),
                                    composite_pixbuf.get_n_channels(),
                                    composite_pixbuf.get_pixels(),
                                ),
                            ),
                        )
                        if VERBOSE:
                            print("   ✓ Composite image set (preview with icon in bottom-right)")
                    except Exception as e:
                        if VERBOSE:
                            print(f"   ✗ Error setting composite image: {e}")
                else:
                    # Fallback to just preview if composite failed
                    if VERBOSE:
                        print("   Composite failed, using preview only")
                    try:
                        preview_data = base64.b64decode(preview_image_base64)
                        stream = Gio.MemoryInputStream.new_from_data(preview_data, None)
                        pixbuf = GdkPixbuf.Pixbuf.new_from_stream(stream, None)
                        notification.set_hint(
                            "image-data",
                            GLib.Variant(
                                "(iiibiiay)",
                                (
                                    pixbuf.get_width(),
                                    pixbuf.get_height(),
                                    pixbuf.get_rowstride(),
                                    pixbuf.get_has_alpha(),
                                    pixbuf.get_bits_per_sample(),
                                    pixbuf.get_n_channels(),
                                    pixbuf.get_pixels(),
                                ),
                            ),
                        )
                        if VERBOSE:
                            print("   ✓ Preview image set")
                    except Exception as e:
                        if VERBOSE:
                            print(f"   ✗ Error setting preview: {e}")

            elif preview_image_base64:
                # Only preview available
                try:
                    preview_data = base64.b64decode(preview_image_base64)
                    stream = Gio.MemoryInputStream.new_from_data(preview_data, None)
                    pixbuf = GdkPixbuf.Pixbuf.new_from_stream(stream, None)

                    # Use set_hint with image-data for preview
                    notification.set_hint(
                        "image-data",
                        GLib.Variant(
                            "(iiibiiay)",
                            (
                                pixbuf.get_width(),
                                pixbuf.get_height(),
                                pixbuf.get_rowstride(),
                                pixbuf.get_has_alpha(),
                                pixbuf.get_bits_per_sample(),
                                pixbuf.get_n_channels(),
                                pixbuf.get_pixels(),
                            ),
                        ),
                    )

                    if VERBOSE:
                        print("   ✓ Preview image set (256x256)")
                except Exception as e:
                    if VERBOSE:
                        print(f"   ✗ Error processing preview: {e}")

            elif icon_base64:
                # Only icon available (no preview)
                try:
                    icon_data = base64.b64decode(icon_base64)
                    stream = Gio.MemoryInputStream.new_from_data(icon_data, None)
                    pixbuf = GdkPixbuf.Pixbuf.new_from_stream(stream, None)

                    # Use set_hint with image-data (consistent with preview handling)
                    notification.set_hint(
                        "image-data",
                        GLib.Variant(
                            "(iiibiiay)",
                            (
                                pixbuf.get_width(),
                                pixbuf.get_height(),
                                pixbuf.get_rowstride(),
                                pixbuf.get_has_alpha(),
                                pixbuf.get_bits_per_sample(),
                                pixbuf.get_n_channels(),
                                pixbuf.get_pixels(),
                            ),
                        ),
                    )

                    if VERBOSE:
                        print("   ✓ Icon set (128x128)")
                except Exception as e:
                    if VERBOSE:
                        print(f"   ✗ Error processing icon: {e}")

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

    print(f"{Colors.HEADER}🚀 Starting Linux notification receiver{Colors.ENDC}")
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
