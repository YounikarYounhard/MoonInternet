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
	// The largest tunnel MTU we will use, whatever the config asks for.
	//
	// Measured against a live 3.1 server on a path that carries 1500 bytes unfragmented: 1340
	// worked, 1360 did not, sharply. So AmneziaWG 3 spends about 160 bytes a packet — the 60 of
	// ordinary WireGuard plus its content padding and random trailers — while 3x-ui still writes
	// the plain-WireGuard 1380 into the config. Obeying it gave a tunnel where small packets went
	// through and anything full-sized was dropped: DNS answered, pings were fine, pages hung.
	//
	// 1280 is the IPv6 minimum, so every path has to carry it, and it leaves 220 bytes of headroom
	// where 160 were needed.
	//
	// ponytail: a fixed ceiling rather than path-MTU discovery. Costs a few percent of throughput;
	// worth doing properly only if someone is counting megabits.
	maxMtu = 1280
	// How long a silent datagram flow is held open. Long enough to sit between QUIC keepalives,
	// short enough that a phone which has moved on is not still holding hundreds of them.
	udpIdle = 90 * time.Second
)

func main() {
	uapiPath := flag.String("uapi", "", "file holding the UAPI document for IpcSet")
	listen := flag.String("listen", "127.0.0.1:1090", "address for the SOCKS5 listener")
	addrs := flag.String("addr", "", "tunnel addresses, comma separated (10.8.1.2/32)")
	dns := flag.String("dns", "1.1.1.1", "resolvers inside the tunnel, comma separated")
	mtu := flag.Int("mtu", maxMtu, "tunnel MTU; an upper bound of "+strconv.Itoa(maxMtu)+" applies")
	verbose := flag.Bool("v", false, "log what the device is doing")
	flag.Parse()

	if *uapiPath == "" || *addrs == "" {
		fmt.Fprintln(os.Stderr, "awgproxy: -uapi and -addr are both required")
		os.Exit(2)
	}

	uapi, err := os.ReadFile(*uapiPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "awgproxy: cannot read the uapi file:", err)
		os.Exit(1)
	}

	localAddrs, err := parseAddrs(*addrs)
	if err != nil {
		fmt.Fprintln(os.Stderr, "awgproxy:", err)
		os.Exit(1)
	}
	resolvers, err := parseAddrs(*dns)
	if err != nil {
		fmt.Fprintln(os.Stderr, "awgproxy:", err)
		os.Exit(1)
	}

	if *mtu <= 0 || *mtu > maxMtu {
		*mtu = maxMtu
	}

	// A userspace interface: no TUN device, no admin rights, nothing for another VPN to collide
	// with. tnet is a full TCP/IP stack whose packets go through the tunnel — ours rather than the
	// one amneziawg-go ships, for the two reasons set out in tunnel.go.
	tun, tnet, err := createTunnel(localAddrs, resolvers, *mtu)
	if err != nil {
		fmt.Fprintln(os.Stderr, "awgproxy: cannot create the userspace interface:", err)
		os.Exit(1)
	}

	level := device.LogLevelError
	if *verbose {
		level = device.LogLevelVerbose
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(level, "awg "))

	if err := dev.IpcSet(string(uapi)); err != nil {
		fmt.Fprintln(os.Stderr, "awgproxy: the core refused the config:", err)
		os.Exit(1)
	}
	if err := dev.Up(); err != nil {
		fmt.Fprintln(os.Stderr, "awgproxy: cannot bring the device up:", err)
		os.Exit(1)
	}
	defer dev.Close()

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		fmt.Fprintln(os.Stderr, "awgproxy: cannot listen on", *listen+":", err)
		os.Exit(1)
	}
	defer ln.Close()

	// The one line the app waits for: the port is open, not merely the process alive. Same knock
	// the byedpi runner learned to need.
	fmt.Println("awgproxy listening on", *listen)

	for {
		c, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		go serve(c, tnet)
	}
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
