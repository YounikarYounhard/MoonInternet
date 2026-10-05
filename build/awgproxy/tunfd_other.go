//go:build !linux

package main

import (
	"errors"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

func openTunFD(int) (tun.Device, error) {
	return nil, errors.New("-tunfd is for Android, where the VpnService hands one over")
}
