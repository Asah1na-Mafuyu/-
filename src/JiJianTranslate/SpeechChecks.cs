using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Media;

namespace JiJianTranslate;

internal static class SpeechChecks
{
    // Explicit live check: only public example phrases go to Microsoft. No DeepSeek call/key.
    internal static async Task<int> RunAsync(string folder)
    {
        Directory.CreateDirectory(folder);var report=new List<string>();
        var samples=new Dictionary<string,string>{{"简体中文","你好，欢迎使用极简翻译。"},{"繁體中文","你好，歡迎使用極簡翻譯。"},{"English","Hello. Welcome to simple translation."},{"日本語","こんにちは。翻訳へようこそ。"},{"한국어","안녕하세요. 번역을 시작합니다."},{"Français","Bonjour. Bienvenue."},{"Deutsch","Hallo. Herzlich willkommen."},{"Español","Hola. Bienvenidos."},{"Русский","Здравствуйте. Добро пожаловать."}};
        foreach(var pair in samples) {
            string path=Path.Combine(folder,EdgeSpeechClient.Voice(pair.Key)+".mp3");
            try {
                using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(50));byte[] bytes=await EdgeSpeechClient.SynthesizeAsync(pair.Value,pair.Key,0,timeout.Token);
                await File.WriteAllBytesAsync(path,bytes,timeout.Token);var player=new MediaPlayer{Volume=0};
                try {
                    var opened=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);player.MediaOpened+=(_,_)=>opened.TrySetResult();player.MediaFailed+=(_,e)=>opened.TrySetException(e.ErrorException);player.Open(new Uri(path));await opened.Task.WaitAsync(TimeSpan.FromSeconds(12));
                    if(!player.NaturalDuration.HasTimeSpan||player.NaturalDuration.TimeSpan.TotalMilliseconds<100)throw new InvalidDataException("无法解码 MP3。");
                    player.Play();await Task.Delay(300);if(player.Position.TotalMilliseconds<50)throw new InvalidDataException("播放器未开始播放。");
                    report.Add("PASS "+pair.Key+" "+EdgeSpeechClient.Voice(pair.Key)+" "+bytes.Length+" bytes, "+player.NaturalDuration.TimeSpan.TotalSeconds.ToString("F2")+"s, playback position "+player.Position.TotalMilliseconds.ToString("F0")+"ms");
                }finally{player.Close();}
            }catch(Exception ex){report.Add("FAIL "+pair.Key+" "+ex.GetType().Name+": "+ex.Message);}
            await File.WriteAllLinesAsync(Path.Combine(folder,"speech-report.txt"),report,new UTF8Encoding(false));
        }
        MainWindow? window=null;
        try {
            string root=Path.Combine(folder,"ui-state");window=new MainWindow(new LocalStore(root),null,true);window.PreviewSample(false);
            window.SpeakButton.RaiseEvent(new System.Windows.RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
            if(window.SpeakButton.Content.ToString()!="停止")throw new InvalidOperationException("准备语音期间无法停止。");
            var until=DateTime.UtcNow.AddSeconds(50);while(!window.SpeechStatus.Text.StartsWith("正在朗读",StringComparison.Ordinal)&&DateTime.UtcNow<until){if(window.SpeakButton.Content.ToString()!="停止")throw new InvalidOperationException(window.SpeechStatus.Text);await Task.Delay(50);}
            if(!window.SpeechStatus.Text.StartsWith("正在朗读",StringComparison.Ordinal))throw new TimeoutException("朗读未开始。");
            await Task.Delay(150);window.SpeakButton.RaiseEvent(new System.Windows.RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));await Task.Delay(200);
            if(window.SpeakButton.Content.ToString()!="朗读"||window.SpeechStatus.Text.Length>0||Directory.GetFiles(Path.Combine(root,"speech-cache"),"*.mp3").Length!=0)throw new InvalidOperationException("停止后音频或状态未清理。");
            window.SpeakButton.RaiseEvent(new System.Windows.RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));window.InputBox.Text="modified during speech";await Task.Delay(250);
            if(window.SpeakButton.IsEnabled||window.SpeechStatus.Text.Length>0)throw new InvalidOperationException("修改原文后恢复了旧语音。");
            report.Add("PASS UI read button without DeepSeek Key, stop during playback/loading, source invalidation, temporary audio cleanup");
        }catch(Exception ex){report.Add("FAIL UI speech "+ex.GetType().Name+": "+ex.Message);}
        finally{window?.Close();}
        await File.WriteAllLinesAsync(Path.Combine(folder,"speech-report.txt"),report,new UTF8Encoding(false));
        return report.Exists(x=>x.StartsWith("FAIL",StringComparison.Ordinal))?1:0;
    }
}
