// 代理池测速服务器：合并多个订阅，测速筛选出真正可用的节点，输出 Clash(mihomo) 配置文件。
package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"proxypool/internal/config"
	"proxypool/internal/logx"
	"proxypool/internal/runner"
	"proxypool/internal/scheduler"
	"proxypool/internal/server"
)

func main() {
	cfgPath := flag.String("c", "config.yaml", "配置文件路径")
	listen := flag.String("listen", "", "覆盖监听地址（如 0.0.0.0:18080）")
	token := flag.String("token", "", "覆盖 API 访问令牌")
	once := flag.Bool("once", false, "执行一次「拉取+测速+生成」后退出，适合配合计划任务")
	noScheduler := flag.Bool("no-scheduler", false, "禁用定时任务（仅本次运行生效）")
	flag.Parse()

	logBuf := logx.New(1200, os.Stdout)

	store, err := config.Load(*cfgPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "加载配置失败: %v\n", err)
		os.Exit(1)
	}
	if *listen != "" || *token != "" || *noScheduler {
		store.Override(func(c *config.Config) {
			if *listen != "" {
				c.Server.Listen = *listen
			}
			if *token != "" {
				c.Server.Token = *token
			}
			if *noScheduler {
				c.Scheduler.Enabled = false
				c.Scheduler.RunOnStart = false
			}
		})
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	logBuf.Info("配置文件：%s", store.Path())
	taskRunner := runner.New(store, logBuf)

	if *once {
		snap, err := taskRunner.Run(ctx, "命令行执行", runner.Options{})
		printSummary(logBuf, store, snap)
		if err != nil {
			os.Exit(1)
		}
		return
	}

	httpServer, err := server.New(store, taskRunner, logBuf)
	if err != nil {
		fmt.Fprintf(os.Stderr, "初始化 HTTP 服务失败: %v\n", err)
		os.Exit(1)
	}

	cfg := store.Get()
	scheduler.New(store, taskRunner, logBuf).Start(ctx)

	srv := &http.Server{
		Addr:              cfg.Server.Listen,
		Handler:           httpServer.Handler(),
		ReadHeaderTimeout: 15 * time.Second,
	}
	go func() {
		addr := displayAddr(cfg.Server.Listen)
		suffix := ""
		if cfg.Server.Token != "" {
			suffix = "?token=" + cfg.Server.Token
		}
		logBuf.Info("管理页面：http://%s/%s", addr, suffix)
		logBuf.Info("订阅地址：http://%s/clash.yaml%s", addr, suffix)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logBuf.Error("HTTP 服务异常退出：%v", err)
			stop()
		}
	}()

	<-ctx.Done()
	logBuf.Info("正在退出…")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}

func printSummary(log *logx.Buffer, store *config.Store, snap *runner.Snapshot) {
	if snap == nil {
		log.Error("任务未产生结果")
		return
	}
	log.Info("节点统计：解析 %d，去重 %d，可用 %d，写入配置 %d", snap.Total, snap.Duplicated, snap.Alive, snap.Active)
	for _, sub := range snap.Subscriptions {
		if sub.OK {
			log.Info("  订阅「%s」：%d 个节点", sub.Name, sub.NodeCount)
		} else {
			log.Warn("  订阅「%s」：%s", sub.Name, sub.Error)
		}
	}
	if snap.Output != "" {
		log.Info("输出文件：%s", snap.Output)
	}
}

func displayAddr(addr string) string {
	addr = strings.TrimSpace(addr)
	switch {
	case strings.HasPrefix(addr, "0.0.0.0:"):
		return "127.0.0.1:" + strings.TrimPrefix(addr, "0.0.0.0:")
	case strings.HasPrefix(addr, "[::]:"):
		return "127.0.0.1:" + strings.TrimPrefix(addr, "[::]:")
	case strings.HasPrefix(addr, ":"):
		return "127.0.0.1" + addr
	default:
		return addr
	}
}
