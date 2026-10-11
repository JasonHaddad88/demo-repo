import socket
import threading
import unittest

from portwarden import discovery
from portwarden.runner import FakeRunner

SS_OUTPUT = """\
tcp   LISTEN 0      4096       127.0.0.1:631        0.0.0.0:*    users:(("cupsd",pid=1234,fd=7))
tcp   LISTEN 0      511              *:80           *:*          users:(("nginx",pid=10,fd=6),("nginx",pid=11,fd=6))
tcp   LISTEN 0      4096         [::1]:631          [::]:*
udp   UNCONN 0      0       127.0.0.54:53           0.0.0.0:*    users:(("systemd-resolve",pid=800,fd=18))
tcp   ESTAB  0      0        10.0.0.5:22       10.0.0.9:51234    users:(("sshd",pid=99,fd=4))
"""

NETSTAT_OUTPUT = """
Active Connections

  Proto  Local Address          Foreign Address        State           PID
  TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       928
  TCP    127.0.0.1:5432         0.0.0.0:0              LISTENING       4120
  TCP    192.168.1.8:139        0.0.0.0:0              LISTENING       4
  TCP    127.0.0.1:49670        127.0.0.1:49671        ESTABLISHED     7788
  UDP    0.0.0.0:500            *:*                                    1140
"""

LSOF_OUTPUT = """\
COMMAND     PID USER   FD   TYPE             DEVICE SIZE/OFF NODE NAME
launchd       1 root   28u  IPv6 0x4f0d1a2b3c4d5e6f      0t0  TCP *:22 (LISTEN)
postgres    501 jason  7u   IPv4 0x1a2b3c4d5e6f7a8b      0t0  TCP 127.0.0.1:5432 (LISTEN)
mDNSRespo   190 nobody 9u   IPv4 0x9a8b7c6d5e4f3a2b      0t0  UDP *:5353
Google      933 jason  40u  IPv4 0xaabbccddeeff0011      0t0  TCP 127.0.0.1:62000->127.0.0.1:443 (ESTABLISHED)
"""

PROC_NET_TCP = """\
  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:0277 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 24680 1 0000 100 0 0 10 0
   1: 0100007F:1F90 0100007F:D431 01 00000000:00000000 00:00000000 00000000  1000        0 24681 1 0000 20 0 0 10 0
"""


class PortSpecTests(unittest.TestCase):
    def test_single_ports_and_ranges(self):
        self.assertEqual(discovery.parse_port_spec("22,80,8000-8003"), [22, 80, 8000, 8001, 8002, 8003])

    def test_reversed_range_is_accepted(self):
        self.assertEqual(discovery.parse_port_spec("90-88"), [88, 89, 90])

    def test_whitespace_and_empty_chunks(self):
        self.assertEqual(discovery.parse_port_spec(" 80 , , 81 "), [80, 81])

    def test_out_of_range_rejected(self):
        for spec in ("0", "70000", "1-70000", "-5"):
            with self.subTest(spec=spec), self.assertRaises(ValueError):
                discovery.parse_port_spec(spec)

    def test_default_spec_parses(self):
        ports = discovery.parse_port_spec(discovery.DEFAULT_SCAN_PORTS)
        self.assertIn(22, ports)
        self.assertIn(8080, ports)
        self.assertIn(27017, ports)


class SplitHostPortTests(unittest.TestCase):
    def test_forms(self):
        self.assertEqual(discovery._split_host_port("127.0.0.1:631"), ("127.0.0.1", 631))
        self.assertEqual(discovery._split_host_port("[::1]:631"), ("::1", 631))
        self.assertEqual(discovery._split_host_port("*:80"), ("0.0.0.0", 80))
        self.assertEqual(discovery._split_host_port("fe80::1%eth0:53"), ("fe80::1", 53))
        self.assertIsNone(discovery._split_host_port("garbage"))


class SsParsingTests(unittest.TestCase):
    def setUp(self):
        self.listeners = discovery.parse_ss(SS_OUTPUT)

    def test_established_connections_are_skipped(self):
        self.assertNotIn(22, [item.port for item in self.listeners])

    def test_tcp_listener_with_pid(self):
        cups = next(item for item in self.listeners if item.port == 631 and item.address == "127.0.0.1")
        self.assertEqual(cups.pids, [1234])
        self.assertEqual(cups.process, "cupsd")
        self.assertEqual(cups.scope, "loopback")

    def test_multiple_pids_on_one_socket(self):
        nginx = next(item for item in self.listeners if item.port == 80)
        self.assertEqual(nginx.pids, [10, 11])
        self.assertEqual(nginx.scope, "all-interfaces")

    def test_socket_without_user_info(self):
        v6 = next(item for item in self.listeners if item.address == "::1")
        self.assertEqual(v6.pids, [])
        self.assertEqual(v6.process, "unknown")

    def test_udp_unconn_is_a_listener(self):
        dns = next(item for item in self.listeners if item.proto == "udp")
        self.assertEqual((dns.port, dns.process), (53, "systemd-resolve"))


class NetstatParsingTests(unittest.TestCase):
    def setUp(self):
        self.listeners = discovery.parse_netstat(NETSTAT_OUTPUT)

    def test_only_listening_tcp_and_udp(self):
        ports = sorted(item.port for item in self.listeners)
        self.assertEqual(ports, [135, 139, 500, 5432])

    def test_pid_column(self):
        postgres = next(item for item in self.listeners if item.port == 5432)
        self.assertEqual(postgres.pids, [4120])

    def test_udp_pid_is_last_column(self):
        udp = next(item for item in self.listeners if item.proto == "udp")
        self.assertEqual(udp.pids, [1140])


class LsofParsingTests(unittest.TestCase):
    def setUp(self):
        self.listeners = discovery.parse_lsof(LSOF_OUTPUT)

    def test_header_and_connections_skipped(self):
        ports = sorted(item.port for item in self.listeners)
        self.assertEqual(ports, [22, 5353, 5432])

    def test_fields(self):
        postgres = next(item for item in self.listeners if item.port == 5432)
        self.assertEqual((postgres.process, postgres.pids, postgres.proto), ("postgres", [501], "tcp"))


class ProcNetParsingTests(unittest.TestCase):
    def test_listening_rows_only(self):
        owners = {"24680": (42, "redis-server")}
        listeners = discovery.parse_proc_net(PROC_NET_TCP, "tcp", owners)
        self.assertEqual(len(listeners), 1)
        self.assertEqual(listeners[0].address, "127.0.0.1")
        self.assertEqual(listeners[0].port, 631)
        self.assertEqual(listeners[0].processes, ["redis-server"])

    def test_hex_address_decoding(self):
        self.assertEqual(discovery._hex_to_address("0100007F"), "127.0.0.1")
        self.assertEqual(discovery._hex_to_address("00000000"), "0.0.0.0")
        self.assertEqual(
            discovery._hex_to_address("00000000000000000000000001000000"), "::1"
        )


class BackendSelectionTests(unittest.TestCase):
    def test_linux_prefers_ss(self):
        run = FakeRunner()
        run.add(["ss"], stdout=SS_OUTPUT)
        listeners = discovery.listening_sockets(run=run, system="Linux")
        self.assertTrue(listeners)
        self.assertEqual(run.calls[0][0], "ss")

    def test_windows_uses_netstat(self):
        run = FakeRunner()
        run.add(["netstat"], stdout=NETSTAT_OUTPUT)
        listeners = discovery.listening_sockets(run=run, system="Windows")
        self.assertEqual(sorted({item.port for item in listeners}), [135, 139, 500, 5432])

    def test_macos_uses_lsof(self):
        run = FakeRunner()
        run.add(["lsof"], stdout=LSOF_OUTPUT)
        listeners = discovery.listening_sockets(run=run, system="Darwin")
        self.assertIn(5432, {item.port for item in listeners})

    def test_dedupe_merges_duplicate_sockets(self):
        run = FakeRunner()
        run.add(["lsof"], stdout=LSOF_OUTPUT)  # both lsof calls return the same rows
        listeners = discovery.listening_sockets(run=run, system="Darwin")
        keys = [item.key for item in listeners]
        self.assertEqual(len(keys), len(set(keys)))


class ScanTests(unittest.TestCase):
    def setUp(self):
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(8)
        self.port = self.server.getsockname()[1]
        self.stop = threading.Event()

        def accept_loop():
            self.server.settimeout(0.2)
            while not self.stop.is_set():
                try:
                    conn, _ = self.server.accept()
                    conn.close()
                except (OSError, socket.timeout):
                    continue

        self.thread = threading.Thread(target=accept_loop, daemon=True)
        self.thread.start()
        self.addCleanup(self._teardown)

    def _teardown(self):
        self.stop.set()
        self.thread.join(timeout=2)
        self.server.close()

    def test_finds_a_real_listener(self):
        self.assertTrue(discovery.probe_port(self.port))
        self.assertEqual(discovery.scan_ports([self.port]), [self.port])

    def test_closed_port_is_not_reported(self):
        closed = socket.socket()
        closed.bind(("127.0.0.1", 0))
        free_port = closed.getsockname()[1]
        closed.close()
        self.assertEqual(discovery.scan_ports([free_port], timeout=0.1), [])

    def test_scan_refuses_non_loopback_hosts(self):
        with self.assertRaises(ValueError):
            discovery.scan_ports([80], host="192.168.1.1")

    def test_empty_port_list(self):
        self.assertEqual(discovery.scan_ports([]), [])

    def test_inventory_marks_reachability_and_adds_scan_only_rows(self):
        run = FakeRunner()
        run.add(["ss"], stdout=SS_OUTPUT)
        rows = discovery.inventory(ports=[631, self.port], run=run, system="Linux")
        scanned = next(item for item in rows if item.port == self.port)
        self.assertEqual(scanned.source, "scan")
        self.assertTrue(scanned.reachable)
        cups = next(item for item in rows if item.port == 631 and item.address == "127.0.0.1")
        # Requested in the scan, so reachability is resolved either way.
        self.assertEqual(cups.reachable, discovery.probe_port(631))


if __name__ == "__main__":
    unittest.main()
