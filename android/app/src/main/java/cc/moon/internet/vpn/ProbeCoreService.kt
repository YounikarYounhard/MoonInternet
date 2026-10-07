package cc.moon.internet.vpn

/**
 * [CoreService] again, in a process of its own (`:probe`), for the stability probe's AmneziaWG
 * engine. awgproxy keeps one engine per process, and the connection's may be running in `:core`
 * at the same time.
 */
class ProbeCoreService : CoreService()
