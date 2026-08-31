package main

import (
	"errors"
	"log/slog"
	"time"
)

// ================= 图片处理并发闸门 =================
//
// ★ 为什么需要 ★
//
// 解码一张图的峰值内存与**像素数**成正比，而与文件大小几乎无关
// （JPEG 压得越狠，同样 20MB 的文件解出来越大）。上限 MaxPixels 默认 12M 像素
// （0828 由 64M 下调，后台 album.photo_max_megapixels 可调、限超管），
// 解成 RGBA 是 12M × 4B ≈ **48MB**；而解出来的 src 要一直活到缩略图与
// 预览图都写完（storeMediaFile 里连做两次 writeFit），期间缩放自身还要
// 再占几十 MB（见 avatar_worker.go scaleInto 的公式）。
//
// 此前全仓**没有任何并发限制**：N 个用户同时上传就是 N × 数百 MB。
// 单张的上限管住了"一张能有多大"，但没人管住"同时有几张"，
// 于是容器内存曲线完全由外部请求节奏决定 —— 这也是 0827 生产 OOM 的放大器：
// memStore 泄露把基线内存慢慢推高，某次多人同时传照片就成了压垮进程的那一下。
//
// 闸门放在这里而不是靠 Nginx 限并发：Nginx 限的是连接数，而请求可能在
// 上传阶段（读 body）就占着连接却还没开始解码，两者不是一回事。

// maxConcurrentDecodes 同时进行的图片解码作业数。
//
// 3 是按「单张最坏约 110MB（源图 48MB + 派生图生成峰值约 60MB）× 3 ≈ 330MB」
// 估的，留给小规格 VPS 一点余量。0828 之前这个数是 768MB —— 当时像素上限 64M
// 且缩放走单段 CatmullRom，两处一起改完才把闸门的实际封顶降下来。
// 不设成 1：情侣双方同时传照片是完全正常的使用方式，串行化会让两个人互相等。
// 也不设成 CPU 核数：这里的瓶颈是内存不是 CPU，跟核数没关系。
const maxConcurrentDecodes = 3

// decodeAcquireTimeout 等待闸门的最长时间。
//
// 必须有超时。无限等待会让请求在队列里越堆越多，每条都占着一个 goroutine 与
// 一个已落盘的临时文件 —— 那只是把 OOM 换成了磁盘和 goroutine 泄露。
// 超时后明确回一个"服务器忙"，客户端可以重试；这比拖到 http.Server 的
// WriteTimeout 被动断开要好得多（后者在客户端看来是"上传莫名失败"）。
const decodeAcquireTimeout = 20 * time.Second

// errImageBusy 闸门等待超时。handler 应转成 503 + 可重试的中文提示。
var errImageBusy = errors.New("image processing busy")

// imageDecodeSem 是全局闸门。用带缓冲 channel 而不是 sync.Semaphore
// （标准库没有）或 golang.org/x/sync/semaphore（不想为一个 5 行的东西加依赖）。
var imageDecodeSem = make(chan struct{}, maxConcurrentDecodes)

// withImageBudget 在闸门内执行 fn。
//
// ★ 必须包住「解码 + 所有派生图生成」这一整段，不能只包解码 ★
// 解码出的 src 会一直活到最后一次 writeFit 结束；若只在 decode 期间持闸，
// 释放后 src 仍在内存里，N 个请求照样能各持一份 —— 等于没限。
//
// 调用点只有两处（相册 storeMediaFile、头像 processAvatar），
// 都在注释里指回了这里。新增图片处理路径时必须一并包上。
func withImageBudget(fn func() error) error {
	select {
	case imageDecodeSem <- struct{}{}:
		defer func() { <-imageDecodeSem }()
		return fn()
	case <-time.After(decodeAcquireTimeout):
		slog.Warn("image decode budget exhausted, rejecting request",
			"limit", maxConcurrentDecodes, "waited", decodeAcquireTimeout)
		return errImageBusy
	}
}
