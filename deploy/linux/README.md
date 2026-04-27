# Linux deployment (untested)

> **Status**: not tested yet. Bleak supports BlueZ ≥ 5.50, so the
> listener should work, but the systemd unit and the auto-recovery
> path are not validated. PRs welcome.

## Prerequisites

- Python 3.10+
- BlueZ 5.50 or newer (`bluetoothctl --version`)
- Mosquitto / EMQX broker reachable from this host
- Optional: a USB BT 5.0 dongle if the integrated controller is
  unreliable (see [`docs/DEPLOY.md`](../../docs/DEPLOY.md) §4)

## Install

```bash
sudo mkdir -p /opt/mondeer-wanka-c1-listener
sudo chown $USER:$USER /opt/mondeer-wanka-c1-listener
git clone https://github.com/MicheleMercuri/mondeer-wanka-c1-listener.git /opt/mondeer-wanka-c1-listener
cd /opt/mondeer-wanka-c1-listener

python3 -m venv .venv
.venv/bin/pip install -r listener/requirements.txt

cp listener/config.example.yaml listener/config.yaml
$EDITOR listener/config.yaml         # fill in HA / MQTT / profiles

# Make sure the runtime user can talk to BlueZ
sudo usermod -aG bluetooth $USER
# log out / log in for group membership to take effect
```

Test the listener manually first:

```bash
cd /opt/mondeer-wanka-c1-listener
.venv/bin/python -m listener.scale_listener
```

Step on the scale; you should see `scale advertising` → `connect OK` →
`MQTT publish ... OK`.

## systemd unit

Once the manual run works, install the unit:

```bash
sudo cp deploy/linux/bilancia-mondeer.service.example /etc/systemd/system/bilancia-mondeer.service
sudoedit /etc/systemd/system/bilancia-mondeer.service   # fix User=, paths
sudo systemctl daemon-reload
sudo systemctl enable --now bilancia-mondeer
journalctl -u bilancia-mondeer -f
```

## Notes on auto-recovery

The Windows-only auto-recovery (`Restart-Service bthserv`) is a no-op
on Linux. If you hit the same issue (radio stuck after N failures),
the equivalent recovery is `sudo systemctl restart bluetooth`. A
patch to make this work cross-platform is welcome.
