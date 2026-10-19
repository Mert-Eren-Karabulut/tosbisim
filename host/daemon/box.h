// simrelayd box mode helpers. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
#pragma once

#define BOX_MAX_KEYDEVS 32

int box_screen(int on);                         // 0 = sleep, 1 = wake
int box_role(const char *role, const char *pkg);
const char *box_charge_node(void);              // "cap+cut" / "cap" / "cut", NULL if no charge control
int box_charge(int percent);                    // 0 ok, -1 failed, -2 unsupported (persisted)
void box_charge_init(void);                     // release the input cut, re-apply the saved limit
void box_charge_check(void);                    // hold the limit (on battery events and periodically)
void box_charge_shutdown(void);                 // release the input cut
int box_uevent_open(void);                      // netlink socket for power_supply events
void box_uevent(int fd);
int box_netblock(int uid, int on);
void box_reap(void);                            // collect finished fire-and-forget children (main loop)              // box mode: block/unblock an app uid's network (iptables)
int box_keys_open(void);                        // open volume-key input devices; returns how many
int box_key_count(void);
int box_power_block(int on);                   // box mode: grab (1) / release (0) the power key devices
int box_key_fd(int i);
void box_key_event(int fd);                     // read events, run the escape gesture
void box_announce(void);
void box_log_capture(void);
void box_log_watch(void);
void box_key_drop(int i);                      // a watched input device went away
void box_power_check(void);                    // release an unconfirmed power key block (main loop)                       // restart the capture when the file stops growing (main loop)                     // rotated copy of our logs in /data/adb/tosbisim/logs                        // DAEMON_UP broadcast to the host app
