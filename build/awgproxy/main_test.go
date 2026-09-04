package main

import (
	"bytes"
	"testing"
)

// The datagram header is the part with sharp edges: it is attacker-adjacent, it is parsed on every
// single packet, and getting the length arithmetic wrong reads off the end of the buffer.
func TestParseDatagram(t *testing.T) {
	t.Run("ipv4", func(t *testing.T) {
		header, target, payload, ok := parseDatagram(
			[]byte{0, 0, 0, 1, 1, 1, 1, 1, 0, 53, 0xab, 0xcd})
		if !ok || target != "1.1.1.1:53" {
			t.Fatalf("got %q, ok=%v", target, ok)
		}
		if !bytes.Equal(payload, []byte{0xab, 0xcd}) {
			t.Fatalf("payload %v", payload)
		}
		// Echoed back verbatim on the reply, so it has to be exactly the address bytes and no more.
		if !bytes.Equal(header, []byte{1, 1, 1, 1, 1, 0, 53}) {
			t.Fatalf("header %v", header)
		}
	})

	t.Run("domain", func(t *testing.T) {
		pkt := append([]byte{0, 0, 0, 3, 5}, "moon."[:5]...)
		pkt = append(pkt, 1, 187, 0x01)
		_, target, payload, ok := parseDatagram(pkt)
		if !ok || target != "moon.:443" || len(payload) != 1 {
			t.Fatalf("got %q, payload %v, ok=%v", target, payload, ok)
		}
	})

	t.Run("ipv6", func(t *testing.T) {
		pkt := []byte{0, 0, 0, 4}
		pkt = append(pkt, make([]byte, 16)...)
		pkt[len(pkt)-1] = 1
		pkt = append(pkt, 0, 53)
		_, target, _, ok := parseDatagram(pkt)
		if !ok || target != "[::1]:53" {
			t.Fatalf("got %q, ok=%v", target, ok)
		}
	})

	t.Run("refused", func(t *testing.T) {
		for name, pkt := range map[string][]byte{
			"fragment":       {0, 0, 1, 1, 1, 1, 1, 1, 0, 53},
			"short header":   {0, 0, 0, 1},
			"address cut":    {0, 0, 0, 1, 1, 1},
			"port cut":       {0, 0, 0, 1, 1, 1, 1, 1, 0},
			"domain cut":     {0, 0, 0, 3, 9, 'm', 'o'},
			"unknown atyp":   {0, 0, 0, 9, 1, 1, 1, 1, 0, 53},
			"nothing at all": {},
		} {
			if _, _, _, ok := parseDatagram(pkt); ok {
				t.Fatalf("%s was accepted", name)
			}
		}
	})
}
