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
	"log"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun/netstack"
)

func main() {
	uapiPath := flag.String("uapi", "", "file holding the UAPI document for IpcSet")
	listen := flag.String("listen", "127.0.0.1:1090", "address for the SOCKS5 listener")
	addrs := flag.String("addr", "", "tunnel addresses, comma separated (10.8.1.2/32)")
	dns := flag.String("dns", "1.1.1.1", "resolvers inside the tunnel, comma separated")
	mtu := flag.Int("mtu", 1420, "tunnel MTU")
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

	// A userspace interface: no TUN device, no admin rights, nothing for another VPN to collide
	// with. tnet is a full TCP/IP stack whose packets go through the tunnel.
	tun, tnet, err := netstack.CreateNetTUN(localAddrs, resolvers, *mtu)
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

// serve answers one SOCKS5 conversation and then splices the two connections together.
//
// Deliberately only CONNECT over TCP. UDP through a SOCKS proxy needs an associate command and a
// second socket, xray does not use it for this outbound, and a half-built version would be worse
// than none — see the note in the app about UDP going direct.
func serve(client net.Conn, tnet *netstack.Net) {
	defer client.Close()

	target, err := socksHandshake(client)
	if err != nil {
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

func socksHandshake(c net.Conn) (string, error) {
	head := make([]byte, 2)
	if _, err := io.ReadFull(c, head); err != nil {
		return "", err
	}
	if head[0] != 5 {
		return "", errors.New("not socks5")
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return "", err
	}
	// No authentication: the listener is on loopback and only our own core dials it.
	if _, err := c.Write([]byte{5, 0}); err != nil {
		return "", err
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(c, req); err != nil {
		return "", err
	}
	if req[1] != 1 { // CONNECT
		c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0}) // command not supported
		return "", errors.New("only CONNECT is supported")
	}

	var host string
	switch req[3] {
	case 1: // IPv4
		b := make([]byte, 4)
		if _, err := io.ReadFull(c, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	case 3: // domain
		l := make([]byte, 1)
		if _, err := io.ReadFull(c, l); err != nil {
			return "", err
		}
		b := make([]byte, l[0])
		if _, err := io.ReadFull(c, b); err != nil {
			return "", err
		}
		host = string(b)
	case 4: // IPv6
		b := make([]byte, 16)
		if _, err := io.ReadFull(c, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	default:
		c.Write([]byte{5, 8, 0, 1, 0, 0, 0, 0, 0, 0}) // address type not supported
		return "", errors.New("unknown address type")
	}

	p := make([]byte, 2)
	if _, err := io.ReadFull(c, p); err != nil {
		return "", err
	}
	port := int(p[0])<<8 | int(p[1])
	return net.JoinHostPort(host, strconv.Itoa(port)), nil
}

var _ = log.Print // keep the import when the verbose path is compiled out
