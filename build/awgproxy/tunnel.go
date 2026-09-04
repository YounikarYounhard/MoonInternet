// The userspace interface the tunnel runs on.
//
// This is our own, rather than amneziawg-go's ready-made tun/netstack, for two reasons that both
// live inside a struct field it does not export:
//
//  1. gVisor's TCP defaults to Reno. Every byte we carry is terminated by that stack, so on a link
//     that loses packets it recovers far more slowly than the phone's own kernel would — which is
//     exactly the comparison a user makes when the official client feels quicker. Cubic is one
//     option away, and unreachable from outside the package.
//  2. Their interface hands each outgoing packet over an unbuffered channel, so a moment's delay in
//     the sender blocks the whole stack rather than dropping a packet, which is what a real network
//     card would do.
//
// The device and the dialling below are ported from amneziawg-go's tun/netstack (MIT,
// Copyright (C) 2017-2025 WireGuard LLC), which is in turn wireguard-go's. The resolver is ours and
// is deliberately much smaller than theirs — plus it remembers answers, where theirs asks again for
// every single connection.
package main

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// How many packets may be waiting to leave the stack before we start dropping them. Dropping is
// the right answer: TCP will resend, whereas blocking stops the stack from doing anything at all,
// including acknowledging what is arriving.
const outgoingQueue = 1024

type netTun struct {
	ep             *channel.Endpoint
	stack          *stack.Stack
	events         chan tun.Event
	notifyHandle   *channel.NotificationHandle
	incomingPacket chan *buffer.View
	done           chan struct{}
	closeOnce      sync.Once
	mtu            int
}

// tunnel is everything reachable through the interface: dialling, and the names to dial by.
type tunnel struct {
	stack     *stack.Stack
	resolvers []netip.Addr
	hasV6     bool

	mu      sync.Mutex
	answers map[string]answer
}

type answer struct {
	addrs []netip.Addr
	until time.Time
}

func createTunnel(localAddresses, resolvers []netip.Addr, mtu int) (tun.Device, *tunnel, error) {
	dev := &netTun{
		ep:             channel.New(1024, uint32(mtu), ""),
		events:         make(chan tun.Event, 10),
		incomingPacket: make(chan *buffer.View, outgoingQueue),
		done:           make(chan struct{}),
		mtu:            mtu,
	}
	dev.stack = stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6},
		HandleLocal:        true,
	})

	// The whole reason this function exists rather than a call to theirs.
	cubic := tcpip.CongestionControlOption("cubic")
	if err := dev.stack.SetTransportProtocolOption(tcp.ProtocolNumber, &cubic); err != nil {
		return nil, nil, fmt.Errorf("could not select cubic: %v", err)
	}
	sack := tcpip.TCPSACKEnabled(true) // off by default, and recovery is hopeless without it
	if err := dev.stack.SetTransportProtocolOption(tcp.ProtocolNumber, &sack); err != nil {
		return nil, nil, fmt.Errorf("could not enable TCP SACK: %v", err)
	}

	dev.notifyHandle = dev.ep.AddNotify(dev)
	if err := dev.stack.CreateNIC(1, dev.ep); err != nil {
		return nil, nil, fmt.Errorf("CreateNIC: %v", err)
	}

	net := &tunnel{stack: dev.stack, resolvers: resolvers, answers: map[string]answer{}}
	var hasV4 bool
	for _, ip := range localAddresses {
		protocol := ipv4.ProtocolNumber
		if ip.Is6() {
			protocol = ipv6.ProtocolNumber
		}
		addr := tcpip.ProtocolAddress{
			Protocol:          protocol,
			AddressWithPrefix: tcpip.AddrFromSlice(ip.AsSlice()).WithPrefix(),
		}
		if err := dev.stack.AddProtocolAddress(1, addr, stack.AddressProperties{}); err != nil {
			return nil, nil, fmt.Errorf("AddProtocolAddress(%v): %v", ip, err)
		}
		if ip.Is4() {
			hasV4 = true
		} else {
			net.hasV6 = true
		}
	}
	if hasV4 {
		dev.stack.AddRoute(tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: 1})
	}
	if net.hasV6 {
		dev.stack.AddRoute(tcpip.Route{Destination: header.IPv6EmptySubnet, NIC: 1})
	}

	dev.events <- tun.EventUp
	return dev, net, nil
}

func (t *netTun) Name() (string, error)    { return "go", nil }
func (t *netTun) File() *os.File           { return nil }
func (t *netTun) Events() <-chan tun.Event { return t.events }
func (t *netTun) MTU() (int, error)        { return t.mtu, nil }
func (t *netTun) BatchSize() int           { return 1 }

func (t *netTun) Read(buf [][]byte, sizes []int, offset int) (int, error) {
	select {
	case view := <-t.incomingPacket:
		n, err := view.Read(buf[0][offset:])
		if err != nil {
			return 0, err
		}
		sizes[0] = n
		return 1, nil
	case <-t.done:
		return 0, os.ErrClosed
	}
}

func (t *netTun) Write(buf [][]byte, offset int) (int, error) {
	for _, b := range buf {
		packet := b[offset:]
		if len(packet) == 0 {
			continue
		}
		pkb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(packet)})
		switch packet[0] >> 4 {
		case 4:
			t.ep.InjectInbound(header.IPv4ProtocolNumber, pkb)
		case 6:
			t.ep.InjectInbound(header.IPv6ProtocolNumber, pkb)
		default:
			pkb.DecRef()
			return 0, syscall.EAFNOSUPPORT
		}
		pkb.DecRef()
	}
	return len(buf), nil
}

func (t *netTun) WriteNotify() {
	pkt := t.ep.Read()
	if pkt == nil {
		return
	}
	view := pkt.ToView()
	pkt.DecRef()
	select {
	case t.incomingPacket <- view:
	case <-t.done:
	default: // queue full — drop it, the way a card under load does
	}
}

func (t *netTun) Close() error {
	t.closeOnce.Do(func() {
		close(t.done) // before the stack, so nothing is left waiting on a channel
		t.stack.RemoveNIC(1)
		t.stack.Close()
		t.ep.RemoveNotify(t.notifyHandle)
		t.ep.Close()
		close(t.events)
	})
	return nil
}

func fullAddress(addr netip.AddrPort) (tcpip.FullAddress, tcpip.NetworkProtocolNumber) {
	protocol := ipv4.ProtocolNumber
	if addr.Addr().Is6() {
		protocol = ipv6.ProtocolNumber
	}
	return tcpip.FullAddress{
		NIC:  1,
		Addr: tcpip.AddrFromSlice(addr.Addr().AsSlice()),
		Port: addr.Port(),
	}, protocol
}

// Dial opens a connection through the tunnel. "tcp" and "udp" only, by address or by name.
func (t *tunnel) Dial(network, address string) (net.Conn, error) {
	host, portText, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 0 || port > 65535 {
		return nil, fmt.Errorf("%q is not a port", portText)
	}

	addrs, err := t.resolve(host)
	if err != nil {
		return nil, err
	}
	// Whatever the name gave us, try them in turn: the first address is not always the live one.
	var lastErr error
	for _, addr := range addrs {
		if addr.Is6() && !t.hasV6 {
			continue // no v6 address on this interface, so nothing could answer
		}
		conn, err := t.dialAddr(network, netip.AddrPortFrom(addr, uint16(port)))
		if err == nil {
			return conn, nil
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no usable address for %q", host)
	}
	return nil, lastErr
}

func (t *tunnel) dialAddr(network string, addr netip.AddrPort) (net.Conn, error) {
	fa, protocol := fullAddress(addr)
	switch network {
	case "tcp", "tcp4", "tcp6":
		return gonet.DialContextTCP(context.Background(), t.stack, fa, protocol)
	case "udp", "udp4", "udp6":
		return gonet.DialUDP(t.stack, nil, &fa, protocol)
	}
	return nil, fmt.Errorf("%q is not a network this carries", network)
}

// resolve turns a host into addresses, asking the tunnel's own resolvers and remembering what they
// said. Theirs asks again on every connection, which on a link like this is a whole extra round
// trip before a page can even start loading.
func (t *tunnel) resolve(host string) ([]netip.Addr, error) {
	if addr, err := netip.ParseAddr(host); err == nil {
		return []netip.Addr{addr}, nil
	}
	if len(t.resolvers) == 0 {
		return nil, errors.New("no resolver inside the tunnel")
	}

	key := strings.ToLower(strings.TrimSuffix(host, "."))
	t.mu.Lock()
	if got, ok := t.answers[key]; ok && time.Now().Before(got.until) {
		t.mu.Unlock()
		return got.addrs, nil
	}
	t.mu.Unlock()

	name, err := dnsmessage.NewName(key + ".")
	if err != nil {
		return nil, err
	}
	types := []dnsmessage.Type{dnsmessage.TypeA}
	if t.hasV6 {
		types = append(types, dnsmessage.TypeAAAA)
	}

	var addrs []netip.Addr
	ttl := uint32(600)
	for _, qtype := range types {
		got, gotTTL, err := t.ask(name, qtype)
		if err != nil {
			continue
		}
		addrs = append(addrs, got...)
		if gotTTL < ttl {
			ttl = gotTTL
		}
	}
	if len(addrs) == 0 {
		return nil, fmt.Errorf("cannot resolve %q through the tunnel", host)
	}

	// Floors and ceilings on the TTL: a one-second record would make the cache pointless, and a
	// day-long one would outlive the tunnel it was learned through.
	if ttl < 30 {
		ttl = 30
	} else if ttl > 3600 {
		ttl = 3600
	}
	t.mu.Lock()
	// ponytail: a plain clear-out rather than an LRU. Entries expire on their own; this is only
	// here so a very long session cannot grow the map without limit.
	if len(t.answers) > 4096 {
		t.answers = map[string]answer{}
	}
	t.answers[key] = answer{addrs: addrs, until: time.Now().Add(time.Duration(ttl) * time.Second)}
	t.mu.Unlock()
	return addrs, nil
}

func (t *tunnel) ask(name dnsmessage.Name, qtype dnsmessage.Type) ([]netip.Addr, uint32, error) {
	var id uint16
	var seed [2]byte
	if _, err := rand.Read(seed[:]); err != nil {
		return nil, 0, err
	}
	id = binary.BigEndian.Uint16(seed[:])

	query, err := (&dnsmessage.Message{
		Header:    dnsmessage.Header{ID: id, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: name, Type: qtype, Class: dnsmessage.ClassINET}},
	}).Pack()
	if err != nil {
		return nil, 0, err
	}

	var lastErr error
	for _, server := range t.resolvers {
		reply, err := t.askOne(server, query, id)
		if err != nil {
			lastErr = err
			continue
		}
		var addrs []netip.Addr
		ttl := uint32(600)
		for _, rr := range reply.Answers {
			var addr netip.Addr
			switch body := rr.Body.(type) {
			case *dnsmessage.AResource:
				addr = netip.AddrFrom4(body.A)
			case *dnsmessage.AAAAResource:
				addr = netip.AddrFrom16(body.AAAA)
			default:
				continue // a CNAME on the way to the address we are collecting
			}
			addrs = append(addrs, addr)
			if rr.Header.TTL < ttl {
				ttl = rr.Header.TTL
			}
		}
		if len(addrs) > 0 {
			return addrs, ttl, nil
		}
		lastErr = errors.New("no address in the answer")
	}
	return nil, 0, lastErr
}

func (t *tunnel) askOne(server netip.Addr, query []byte, id uint16) (*dnsmessage.Message, error) {
	conn, err := t.dialAddr("udp", netip.AddrPortFrom(server, 53))
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(query); err != nil {
		return nil, err
	}
	buf := make([]byte, 1500)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}
	var reply dnsmessage.Message
	if err := reply.Unpack(buf[:n]); err != nil {
		return nil, err
	}
	if reply.Header.ID != id {
		return nil, errors.New("the answer is to a different question")
	}
	if reply.Header.Truncated {
		// Too big for a datagram — the same question over TCP, which is what the bit is asking for.
		return t.askOverTCP(server, query, id)
	}
	return &reply, nil
}

func (t *tunnel) askOverTCP(server netip.Addr, query []byte, id uint16) (*dnsmessage.Message, error) {
	conn, err := t.dialAddr("tcp", netip.AddrPortFrom(server, 53))
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))

	framed := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(framed, uint16(len(query)))
	copy(framed[2:], query)
	if _, err := conn.Write(framed); err != nil {
		return nil, err
	}
	var size [2]byte
	if _, err := io.ReadFull(conn, size[:]); err != nil {
		return nil, err
	}
	body := make([]byte, binary.BigEndian.Uint16(size[:]))
	if _, err := io.ReadFull(conn, body); err != nil {
		return nil, err
	}
	var reply dnsmessage.Message
	if err := reply.Unpack(body); err != nil {
		return nil, err
	}
	if reply.Header.ID != id {
		return nil, errors.New("the answer is to a different question")
	}
	return &reply, nil
}
