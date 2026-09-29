// awgproxy — AmneziaWG as a local SOCKS5 proxy.
//
// The obfuscation is amneziawg-go's, used as a library and not modified: this file only carries
// packets between a SOCKS5 listener and the tunnel's userspace network stack. Keeping our own code
// to the plumbing is the point — the crypto that ships in the installer is the one written by the
// people who define the protocol.
//
// Why SOCKS and not a TUN device: it puts AmneziaWG exactly where Hysteria2 already sits. xray
// dials this port as an ordinary proxy outbound, so every routing rule the app has — the INCY and
// HAPP profiles, the direct rule for the server's own address, per-app splitting — applies to
// AmneziaWG for free. A TUN of its own would have needed all of that written again, and would have
// wanted admin rights on Windows and a second VpnService slot on Android, which there is only one of.
//
// Usage: awgproxy -uapi <file> -listen 127.0.0.1:1090 [-mtu 1380] [-dns 8.8.8.8,8.8.4.4]
package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	// The /v3 path is not decoration: AmneziaWG 3 raised the module's major version, so the
	// 3.x engine is only reachable this way. Without it Go resolves the pre-3 module, whose
	// newest tag (v1.0.4) predates the protocol entirely.
	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
)

const (
	cmdConnect   = 1
	cmdAssociate = 3
	// The tunnel MTU, and a ceiling on what a profile may ask for.
	//
	// This has been argued both ways here, so the reasoning is worth keeping. A full transport
	// packet costs MTU + 20 (IPv4) + 8 (UDP) + S4 + 16 (header) + 16 (tag) — AmneziaWG 3's content
	// padding is bounded by the largest packet already sent, so it never inflates a full-sized one.
	// On a path that carries 1500 that leaves room for about 1417, so 3x-ui's 1380 looks fine and
	// is fine, right up until the path is not 1500.
	//
	// Which, on a mobile carrier, it usually is not. The symptom is nasty precisely because the
	// handshake is small and gets through: the tunnel comes up, reports itself connected, and then
	// drops every full-sized packet, so it sits there carrying nothing. That is what "only on
	// mobile data" turned out to mean, and the official client does the same thing on the same
	// profile — this is not a fault in the client, it is a number that does not fit.
	//
	// 1280 is the IPv6 minimum, so every path must carry it. It is also what amneziawg-android
	// falls back to when a config names no MTU, what the Amnezia Windows client ends up using
	// whatever the config says, and what the common AmneziaWG installers now write into both ends.
	// Following that is not fitting a constant to one connection — it is the value the people who
	// define the protocol settled on for the same reason.
	//
	// ponytail: a fixed ceiling rather than path-MTU discovery. Costs a few percent of throughput
	// on a path that could take more; worth revisiting only with a per-profile override to raise.
	defaultMtu = 1280
	// How long a silent datagram flow is held open. Long enough to sit between QUIC keepalives,
	// short enough that a phone which has moved on is not still holding hundreds of them.
	udpIdle = 90 * time.Second
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "awgproxy:", err)
		os.Exit(1)
	}
}

// What a running core has that a stop has to reach. On the desktop the process simply dies and
// none of this is needed; on Android the core is a library loaded into a process that has to
// outlive it, so there has to be a way back out of the accept loop.
var current struct {
	sync.Mutex
	ln  net.Listener
	dev *device.Device
}

// stopCore ends a run started by [run]. Safe to call when nothing is running, and safe to call
// twice — closing the listener is what breaks the accept loop, and everything else is idempotent.
func stopCore() {
	current.Lock()
	ln, dev := current.ln, current.dev
	current.ln, current.dev = nil, nil
	current.Unlock()
	if ln != nil {
		ln.Close()
	}
	if dev != nil {
		dev.Close()
	}
}

// run is the whole program. It blocks until the listener is closed, and returns an error instead
// of calling os.Exit so that the library build can report a failure rather than take the host
// process down with it.
func run(args []string) error {
	fs := flag.NewFlagSet("awgproxy", flag.ContinueOnError)
	uapiPath := fs.String("uapi", "", "file holding the UAPI document for IpcSet")
	listen := fs.String("listen", "127.0.0.1:1090", "address for the SOCKS5 listener")
	addrs := fs.String("addr", "", "tunnel addresses, comma separated (10.8.1.2/32)")
	dns := fs.String("dns", "1.1.1.1", "resolvers inside the tunnel, comma separated")
	mtu := fs.Int("mtu", defaultMtu, "tunnel MTU; "+strconv.Itoa(defaultMtu)+" is both the default and the ceiling")
	verbose := fs.Bool("v", false, "log what the device is doing")
	if err := fs.Parse(args); err != nil {
		return err
	}

	if *uapiPath == "" || *addrs == "" {
		return errors.New("-uapi and -addr are both required")
	}

	uapi, err := os.ReadFile(*uapiPath)
	if err != nil {
		return fmt.Errorf("cannot read the uapi file: %w", err)
	}

	localAddrs, err := parseAddrs(*addrs)
	if err != nil {
		return err
	}
	resolvers, err := parseAddrs(*dns)
	if err != nil {
		return err
	}

	if *mtu <= 0 || *mtu > defaultMtu {
		*mtu = defaultMtu
	}

	// A userspace interface: no TUN device, no admin rights, nothing for another VPN to collide
	// with. tnet is a full TCP/IP stack whose packets go through the tunnel — ours rather than the
	// one amneziawg-go ships, for the two reasons set out in tunnel.go.
	tun, tnet, err := createTunnel(localAddrs, resolvers, *mtu)
	if err != nil {
		return fmt.Errorf("cannot create the userspace interface: %w", err)
	}

	level := device.LogLevelError
	if *verbose {
		level = device.LogLevelVerbose
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(level, "awg "))

	doc, err := resolveEndpoints(string(uapi))
	if err != nil {
		dev.Close()
		return err
	}
	if err := dev.IpcSet(doc); err != nil {
		dev.Close()
		return fmt.Errorf("the core refused the config: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return fmt.Errorf("cannot bring the device up: %w", err)
	}

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		dev.Close()
		return fmt.Errorf("cannot listen on %s: %w", *listen, err)
	}

	current.Lock()
	current.ln, current.dev = ln, dev
	current.Unlock()

	// One closer, not two. Closing on the way out of here AND in stopCore meant the device was
	// shut twice whenever the app asked it to stop — which is every time you switch away from an
	// AmneziaWG server. stopCore takes what it finds and leaves nothing behind, so calling it
	// again does nothing, and it is the only thing that closes either of these.
	defer stopCore()

	// The one line the app waits for: the port is open, not merely the process alive. Same knock
	// the byedpi runner learned to need.
	fmt.Println("awgproxy listening on", *listen)

	for {
		c, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			continue
		}
		go serve(c, tnet)
	}
}

// resolveEndpoints turns every `endpoint=host:port` in the UAPI document into an address the
// engine will accept.
//
// amneziawg-go parses an endpoint with netip.ParseAddr and refuses anything that is not already a
// literal IP — a peer written as `endpoint=vpn.example.com:51820` is rejected outright with
// "IPC error -22 ... unexpected character". Plenty of providers hand out exactly that, and the
// Amnezia client works with them because it resolves the name itself before starting the engine.
// So do we, here rather than in either app: one lookup, both platforms, and it happens at connect
// time, when the answer is current.
//
// IPv4 first. The tunnel's transport is whatever the host has, and a server reachable over both
// usually has the v4 path configured while the v6 one is an accident of the DNS zone.
func resolveEndpoints(doc string) (string, error) {
	lines := strings.Split(doc, "\n")
	for i, line := range lines {
		value, ok := strings.CutPrefix(line, "endpoint=")
		if !ok {
			continue
		}
		host, port, err := net.SplitHostPort(strings.TrimSpace(value))
		if err != nil {
			continue // not something we understand; let the engine say what it thinks
		}
		if _, err := netip.ParseAddr(host); err == nil {
			continue // already an address
		}
		ips, err := net.LookupIP(host)
		if err != nil || len(ips) == 0 {
			return "", fmt.Errorf("cannot resolve the server address %q: %w", host, err)
		}
		pick := ips[0]
		for _, ip := range ips {
			if ip.To4() != nil {
				pick = ip
				break
			}
		}
		lines[i] = "endpoint=" + net.JoinHostPort(pick.String(), port)
	}
	return strings.Join(lines, "\n"), nil
}

func parseAddrs(list string) ([]netip.Addr, error) {
	var out []netip.Addr
	for _, part := range strings.Split(list, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if i := strings.IndexByte(part, '/'); i >= 0 {
			part = part[:i] // an address with a prefix; netstack wants the address
		}
		a, err := netip.ParseAddr(part)
		if err != nil {
			return nil, fmt.Errorf("%q is not an address: %w", part, err)
		}
		out = append(out, a)
	}
	if len(out) == 0 {
		return nil, errors.New("no usable addresses")
	}
	return out, nil
}

// serve answers one SOCKS5 conversation: CONNECT splices two streams together, ASSOCIATE hands
// over to the datagram relay below.
//
// Refusing UDP here was cheap to write and expensive to use. xray sends every UDP flow to this
// outbound, so a refusal took QUIC — which is most of what YouTube is — and, worse, xray's own DNS
// with it: the resolver queries died, xray fell through to its "localhost" entry, and name lookups
// went out to the phone's own resolver outside the tunnel. The tunnel was up, the ping was good,
// and half the internet answered wrongly or not at all.
func serve(client net.Conn, tnet *tunnel) {
	defer client.Close()

	cmd, target, err := socksHandshake(client)
	if err != nil {
		return
	}
	if cmd == cmdAssociate {
		serveAssociate(client, tnet)
		return
	}

	remote, err := tnet.Dial("tcp", target)
	if err != nil {
		// 0x05 = connection refused, the honest answer rather than a silent close
		client.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()

	if _, err := client.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	go func() { io.Copy(remote, client); remote.Close() }()
	io.Copy(client, remote)
}

func socksHandshake(c net.Conn) (byte, string, error) {
	head := make([]byte, 2)
	if _, err := io.ReadFull(c, head); err != nil {
		return 0, "", err
	}
	if head[0] != 5 {
		return 0, "", errors.New("not socks5")
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return 0, "", err
	}
	// No authentication: the listener is on loopback and only our own core dials it.
	if _, err := c.Write([]byte{5, 0}); err != nil {
		return 0, "", err
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(c, req); err != nil {
		return 0, "", err
	}
	if req[1] != cmdConnect && req[1] != cmdAssociate { // BIND is the one nobody uses
		c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0}) // command not supported
		return 0, "", errors.New("unsupported command")
	}

	var host string
	switch req[3] {
	case 1: // IPv4
		b := make([]byte, 4)
		if _, err := io.ReadFull(c, b); err != nil {
			return 0, "", err
		}
		host = net.IP(b).String()
	case 3: // domain
		l := make([]byte, 1)
		if _, err := io.ReadFull(c, l); err != nil {
			return 0, "", err
		}
		b := make([]byte, l[0])
		if _, err := io.ReadFull(c, b); err != nil {
			return 0, "", err
		}
		host = string(b)
	case 4: // IPv6
		b := make([]byte, 16)
		if _, err := io.ReadFull(c, b); err != nil {
			return 0, "", err
		}
		host = net.IP(b).String()
	default:
		c.Write([]byte{5, 8, 0, 1, 0, 0, 0, 0, 0, 0}) // address type not supported
		return 0, "", errors.New("unknown address type")
	}

	p := make([]byte, 2)
	if _, err := io.ReadFull(c, p); err != nil {
		return 0, "", err
	}
	port := int(p[0])<<8 | int(p[1])
	// The address on an ASSOCIATE is where the client says it will send from, which xray fills in
	// as 0.0.0.0:0 and RFC 1928 lets us ignore. Only CONNECT's address is a destination.
	return req[1], net.JoinHostPort(host, strconv.Itoa(port)), nil
}

// serveAssociate opens the datagram side of the proxy and keeps it for exactly as long as the TCP
// connection that asked for it — that lifetime is the whole reason the control connection stays
// open, and it is what stops a relay socket outliving the core that was using it.
func serveAssociate(client net.Conn, tnet *tunnel) {
	// A real OS socket on loopback: the core sends its datagrams here, and only what comes out of
	// the far side of this function goes into the tunnel.
	local, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		client.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0}) // general failure
		return
	}
	defer local.Close()

	port := local.LocalAddr().(*net.UDPAddr).Port
	// 127.0.0.1 spelled out rather than the 0.0.0.0 many proxies return: "use the address you
	// already dialled" is a convention, and being explicit costs nothing.
	if _, err := client.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)}); err != nil {
		return
	}
	go func() { io.Copy(io.Discard, client); local.Close() }()

	relayDatagrams(local, tnet)
}

// relayDatagrams carries datagrams between the core and the tunnel, one tunnel socket per
// destination so that replies from somewhere else are dropped the way a connected socket drops them.
func relayDatagrams(local *net.UDPConn, tnet *tunnel) {
	var mu sync.Mutex
	flows := map[string]net.Conn{}
	defer func() {
		mu.Lock()
		defer mu.Unlock()
		for _, c := range flows {
			c.Close()
		}
	}()

	buf := make([]byte, 65535)
	for {
		n, from, err := local.ReadFromUDP(buf)
		if err != nil {
			return
		}
		header, target, payload, ok := parseDatagram(buf[:n])
		if !ok {
			continue
		}

		key := from.String() + "|" + target
		mu.Lock()
		flow, known := flows[key]
		mu.Unlock()

		if !known {
			flow, err = tnet.Dial("udp", target)
			if err != nil {
				continue
			}
			mu.Lock()
			flows[key] = flow
			mu.Unlock()
			// The reply header is the client's own address bytes echoed back untouched: re-encoding
			// them would turn the name it asked for into an address it never asked about.
			go readReplies(flow, local, from, append([]byte(nil), header...), func() {
				mu.Lock()
				delete(flows, key)
				mu.Unlock()
			})
		}
		flow.SetReadDeadline(time.Now().Add(udpIdle)) // still in use, even if nothing answers yet
		if _, err := flow.Write(payload); err != nil {
			flow.Close()
		}
	}
}

func readReplies(flow net.Conn, local *net.UDPConn, to *net.UDPAddr, header []byte, done func()) {
	defer func() {
		flow.Close()
		done()
	}()
	buf := make([]byte, 65535)
	for {
		flow.SetReadDeadline(time.Now().Add(udpIdle))
		n, err := flow.Read(buf)
		if err != nil {
			return
		}
		out := make([]byte, 0, 3+len(header)+n)
		out = append(out, 0, 0, 0) // reserved, reserved, fragment
		out = append(out, header...)
		out = append(out, buf[:n]...)
		if _, err := local.WriteToUDP(out, to); err != nil {
			return
		}
	}
}

// parseDatagram splits a SOCKS5 UDP request into the address bytes as sent, the destination, and
// the payload:  RSV RSV FRAG ATYP DST.ADDR DST.PORT DATA.
func parseDatagram(b []byte) (header []byte, target string, payload []byte, ok bool) {
	// Fragments are refused rather than reassembled: RFC 1928 allows that, and no client sends them.
	if len(b) < 5 || b[2] != 0 {
		return nil, "", nil, false
	}
	var host string
	i := 4
	switch b[3] {
	case 1:
		if len(b) < i+4 {
			return nil, "", nil, false
		}
		host = net.IP(b[i : i+4]).String()
		i += 4
	case 3:
		l := int(b[i])
		i++
		if len(b) < i+l {
			return nil, "", nil, false
		}
		host = string(b[i : i+l])
		i += l
	case 4:
		if len(b) < i+16 {
			return nil, "", nil, false
		}
		host = net.IP(b[i : i+16]).String()
		i += 16
	default:
		return nil, "", nil, false
	}
	if len(b) < i+2 {
		return nil, "", nil, false
	}
	port := int(b[i])<<8 | int(b[i+1])
	i += 2
	return b[3:i], net.JoinHostPort(host, strconv.Itoa(port)), b[i:], true
}
