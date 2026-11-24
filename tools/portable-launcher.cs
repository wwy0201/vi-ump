// vi-ump portable single-file launcher (Windows / .NET Framework 4.x)
//
// Why: jpackage only emits an app-image folder (vi-ump.exe + runtime + dlls) or an installer.
// This launcher embeds the whole app-image as a zip resource; on first run it extracts to
// %LOCALAPPDATA%\vi-ump-portable\<version>, then reuses that folder (a .ready marker skips
// re-extraction), so only the very first launch pays the extraction cost.
//
// 体验优化：双击后**立刻**弹一个启动画面（品牌字标 + 进度），解压期间显示进度条，
// 应用窗口出现后自动关掉。否则用户面对 3~5 秒"什么都没发生"，会以为程序没启动。
//
// Compiled by tools/package-portable.ps1, which replaces __VERSION__ with the real version.
// Source is written as UTF-8 (no BOM) and read back with -Encoding UTF8 by the script.
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

internal static class Launcher
{
    private const string Version = "__VERSION__";
    private const string PayloadResource = "PAYLOAD.ZIP";
    private const string LogoResource = "LOGO.PNG";
    private const string AppExe = "vi-ump.exe";

    private static Splash splash;

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hWnd);

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    /// <summary>
    /// 找已经显示出来的应用主窗口。
    /// 按进程名扫而不是盯 Process.Start 的返回值：jpackage 的 exe 会再起一个 JVM 子进程，
    /// 窗口挂在子进程上，父进程的 MainWindowHandle 永远为 0（踩过，启动画面因此滞留）。
    /// </summary>
    private static IntPtr FindAppWindow(DateTime since)
    {
        foreach (string name in new[] { "vi-ump", "javaw" })
        {
            Process[] list;
            try { list = Process.GetProcessesByName(name); } catch { continue; }
            foreach (var p in list)
            {
                try
                {
                    if (p.StartTime < since.AddSeconds(-1)) continue;
                    IntPtr h = p.MainWindowHandle;
                    if (h != IntPtr.Zero && IsWindowVisible(h)) return h;
                }
                catch { }
            }
        }
        return IntPtr.Zero;
    }

    internal static void Log(string msg)
    {
        try
        {
            File.AppendAllText(
                Path.Combine(Path.GetTempPath(), "vi-ump-launcher.log"),
                DateTime.Now.ToString("HH:mm:ss.fff") + "  " + msg + Environment.NewLine);
        }
        catch { }
    }

    /// <summary>每次启动清掉上一次的日志，避免临时目录里越积越大</summary>
    private static void ResetLog()
    {
        try { File.Delete(Path.Combine(Path.GetTempPath(), "vi-ump-launcher.log")); } catch { }
    }

    [STAThread]
    private static int Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        // 先把启动画面建出来，再开后台线程——否则后台线程可能比 splash 先跑，拿到 null
        ResetLog();
        splash = new Splash();
        var thread = new Thread(() => Work(args));
        thread.IsBackground = true;
        thread.Start();

        // 主线程只负责把启动画面转起来；Work 干完会 Invoke 关闭它
        Application.Run(splash);
        return 0;
    }

    private static void Work(string[] args)
    {
        try
        {
            Log("work start");
            string root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "vi-ump-portable",
                Version);
            string target = Path.Combine(root, AppExe);
            string marker = Path.Combine(root, ".ready");

            if (!File.Exists(marker) || !File.Exists(target))
            {
                Log("need extract -> " + root);
                splash.SetStatus("首次启动，正在准备运行组件…");
                splash.SetMarquee(false);
                Extract(root);
                File.WriteAllText(marker, "ok");
                Log("extract done");
            }

            splash.SetStatus("正在启动…");
            splash.SetMarquee(true);
            Log("starting " + target);

            var start = new ProcessStartInfo(target)
            {
                WorkingDirectory = root,
                UseShellExecute = false,
            };
            if (args.Length > 0)
            {
                var quoted = new System.Text.StringBuilder();
                foreach (string a in args)
                {
                    if (quoted.Length > 0) quoted.Append(' ');
                    quoted.Append('"').Append(a.Replace("\"", "\\\"")).Append('"');
                }
                start.Arguments = quoted.ToString();
            }

            var proc = Process.Start(start);
            Log("child pid=" + (proc == null ? -1 : proc.Id));
            DateTime startedAt = DateTime.Now;

            // 等真正的应用窗口出现再撤启动画面。
            // ⚠️ 不能只看 Process.Start 返回的那个进程：jpackage 的 launcher 会再起一个 JVM 子进程，
            // 窗口其实挂在**子进程**上（实测同名的两个 vi-ump 进程，父进程 MainWindowHandle 永远是 0），
            // 只看父进程会一直等不到，启动画面就滞留十几秒。
            // 这里按进程名 "vi-ump" 扫（排除本启动器，它叫 vi-ump-<版本>-portable），并要求窗口真的可见。
            for (int i = 0; i < 200; i++)
            {
                Thread.Sleep(60);
                if (proc != null)
                {
                    proc.Refresh();
                    if (proc.HasExited) throw new Exception("进程提前退出（exit " + proc.ExitCode + "）");
                }
                IntPtr hwnd = FindAppWindow(startedAt);
                if (hwnd != IntPtr.Zero)
                {
                    Log("app window up after " + (int)(DateTime.Now - startedAt).TotalMilliseconds + "ms");
                    try { SetForegroundWindow(hwnd); } catch { }
                    break;
                }
            }
            // 立即撤掉启动画面：不留在屏幕上挡住正式窗口
            splash.CloseSplash();
        }
        catch (Exception ex)
        {
            Log("ERROR " + ex);
            splash.CloseSplash();
            MessageBox.Show(
                "vi-ump 启动失败：" + ex.Message,
                "vi-ump",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
        }
    }

    private static void Extract(string root)
    {
        if (Directory.Exists(root))
        {
            try { Directory.Delete(root, true); }
            catch { /* 残留文件删不掉就让下面覆盖写 */ }
        }
        Directory.CreateDirectory(root);

        using (Stream payload = Assembly.GetExecutingAssembly().GetManifestResourceStream(PayloadResource))
        {
            if (payload == null) throw new Exception("安装资源缺失");

            using (var archive = new ZipArchive(payload, ZipArchiveMode.Read))
            {
                int total = archive.Entries.Count;
                Log("extract entries=" + total);
                int done = 0;
                foreach (var entry in archive.Entries)
                {
                    string dest = Path.GetFullPath(Path.Combine(root, entry.FullName));
                    // 防 Zip-Slip：解出来的路径必须还在 root 里
                    if (!dest.StartsWith(Path.GetFullPath(root), StringComparison.OrdinalIgnoreCase))
                    {
                        throw new Exception("压缩包结构异常");
                    }
                    // 目录项没有文件名（.NET Framework 写出的 zip 可能用反斜杠做分隔符，
                    // 只看 EndsWith("/") 会漏判，结果把目录当成文件写，报 DirectoryNotFound）
                    if (string.IsNullOrEmpty(entry.Name))
                    {
                        Directory.CreateDirectory(dest.TrimEnd(Path.DirectorySeparatorChar));
                    }
                    else
                    {
                        Directory.CreateDirectory(Path.GetDirectoryName(dest));
                        using (Stream src = entry.Open())
                        using (FileStream dst = File.Create(dest))
                        {
                            src.CopyTo(dst);
                        }
                    }
                    done++;
                    if (done % 16 == 0 || done == total) splash.SetProgress(done, total);
                }
                Log("extract loop done " + done);
            }
        }
    }
}

/// <summary>启动画面：无边框、近黑底、左上角字标 + 状态文字 + 底部进度条。</summary>
internal sealed class Splash : Form
{
    private readonly Label status;
    private readonly ProgressBar bar;

    public Splash()
    {
        Text = "vi-ump";
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(11, 11, 15);
        ClientSize = new Size(440, 210);
        ShowInTaskbar = true;
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

        var pic = new PictureBox
        {
            SizeMode = PictureBoxSizeMode.Zoom,
            Bounds = new Rectangle(34, 40, 84, 84),
            Image = LoadLogo(),
        };
        Controls.Add(pic);

        var title = new Label
        {
            Text = "播放器",
            ForeColor = Color.White,
            Font = new Font("Segoe UI", 20, FontStyle.Bold),
            AutoSize = true,
            Location = new Point(132, 44),
        };
        Controls.Add(title);

        var hint = new Label
        {
            Text = "免安装 · 绿色版",
            ForeColor = Color.FromArgb(140, 140, 150),
            Font = new Font("Microsoft YaHei UI", 8),
            AutoSize = true,
            Location = new Point(135, 82),
        };
        Controls.Add(hint);

        status = new Label
        {
            Text = "正在启动…",
            ForeColor = Color.FromArgb(200, 200, 210),
            Font = new Font("Microsoft YaHei UI", 9),
            AutoSize = false,
            Bounds = new Rectangle(34, 124, 372, 22),
        };
        Controls.Add(status);

        bar = new ProgressBar
        {
            Style = ProgressBarStyle.Marquee,
            MarqueeAnimationSpeed = 30,
            Bounds = new Rectangle(34, 154, 372, 9),
        };
        Controls.Add(bar);
    }

    private static Image LoadLogo()
    {
        try
        {
            using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream("LOGO.PNG"))
            {
                if (s == null) return null;
                return Image.FromStream(s);
            }
        }
        catch { return null; }
    }

    public void SetStatus(string text)
    {
        UI(() => status.Text = text);
    }

    public void SetMarquee(bool marquee)
    {
        UI(() =>
        {
            if (marquee)
            {
                bar.Style = ProgressBarStyle.Marquee;
                bar.MarqueeAnimationSpeed = 30;
            }
        });
    }

    public void SetProgress(int done, int total)
    {
        UI(() =>
        {
            if (bar.Style != ProgressBarStyle.Continuous)
            {
                bar.Style = ProgressBarStyle.Continuous;
                bar.Maximum = Math.Max(total, 1);
                bar.Value = 0;
            }
            int v = (int)Math.Min((long)done * 100 / Math.Max(total, 1), 100);
            if (v > bar.Value) bar.Value = v;
            status.Text = "首次启动，正在准备运行组件…  " + v + "%";
        });
    }

    public void CloseSplash()
    {
        try
        {
            Action hide = () =>
            {
                // 先隐藏再关闭：主窗口已经出来了，启动卡不能多留一帧挡住它
                Visible = false;
                Close();
            };
            if (InvokeRequired) BeginInvoke(hide);
            else hide();
        }
        catch { }
    }

    private void UI(Action action)
    {
        try
        {
            if (InvokeRequired) BeginInvoke(action);
            else action();
        }
        catch { }
    }
}
