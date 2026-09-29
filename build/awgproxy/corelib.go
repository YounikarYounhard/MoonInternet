//go:build moonlib

// The library face of awgproxy, built with `-tags moonlib -buildmode=c-shared`.
//
// Why a library at all: since Android 10 an app may not exec a file it wrote into its own data
// directory, which is where a downloaded engine has to live — but it may map one executable and
// load it. So an engine that can be downloaded is a shared library, not a program. (Measured on
// a device, both halves: dlopen of a downloaded .so works, exec of the same file is EACCES.)
//
// The contract is the smallest thing that serves every engine we have, so the loader on the Kotlin
// side is written once and knows nothing about which engine it is holding:
//
//	int  moon_core_main(const char *args);  // argv joined by '\n'; blocks; 0 when stopped cleanly
//	void moon_core_stop(void);              // makes the above return
//
// Newline-separated rather than a real argv because every argument we pass is a path, an address
// or a number — none of them can contain a newline, and it saves the caller a quoting problem it
// would otherwise have to get right in three languages.
//
// The build tag keeps `import "C"` out of the desktop build, which has no C toolchain on the
// machine that makes the releases.
package main

import "C"

import (
	"fmt"
	"os"
	"strings"
)

//export moon_core_main
func moon_core_main(args *C.char) C.int {
	list := []string{}
	for _, a := range strings.Split(C.GoString(args), "\n") {
		if a != "" {
			list = append(list, a)
		}
	}
	if err := run(list); err != nil {
		// Goes to logcat, where the app's own log picks it up. Returning is the important part:
		// a library that called os.Exit here would take the whole app with it.
		fmt.Fprintln(os.Stderr, "awgproxy:", err)
		return 1
	}
	return 0
}

//export moon_core_stop
func moon_core_stop() {
	stopCore()
}
