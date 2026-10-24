using System;
using System.IO;
using System.Net.WebSockets;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Media;

namespace JiJianTranslate;

internal sealed class SpeechPlayback : IDisposable
{
    private readonly string _folder;
    private readonly double _volume;
    private CancellationTokenSource? _current;
    private MediaPlayer? _player;
    internal bool Active => _current is not null;
    internal event Action<string,bool>? Changed;
    internal SpeechPlayback(string root, double volume = 1)
    {
        _folder=Path.Combine(root,"speech-cache");_volume=volume;
        // Only temporary synthesized audio is written here; remove remnants from a prior exit.
        try { if(Directory.Exists(_folder))foreach(string path in Directory.EnumerateFiles(_folder,"speech-*.mp3"))File.Delete(path); }catch(Exception ex)when(ex is IOException or UnauthorizedAccessException){}
    }
    internal void Stop()
    {
        var previous=_current;_current=null;previous?.Cancel();_player?.Close();_player=null;
        Changed?.Invoke("",false);
    }
    internal async Task SpeakAsync(string text,string language,int rate)
    {
        Stop();var chunks=EdgeSpeechClient.Chunks(text);if(chunks.Count==0)return;
        using var cancel=new CancellationTokenSource();_current=cancel;string finalMessage="";bool finalError=false;
        try {
            for(int i=0;i<chunks.Count;i++) {
                Changed?.Invoke(chunks.Count==1?"正在准备语音…":$"正在准备语音 {i+1}/{chunks.Count}…",false);
                byte[] bytes=await EdgeSpeechClient.SynthesizeAsync(chunks[i],language,rate,cancel.Token);cancel.Token.ThrowIfCancellationRequested();
                Directory.CreateDirectory(_folder);string path=Path.Combine(_folder,"speech-"+Guid.NewGuid().ToString("N")+".mp3");
                var player=new MediaPlayer{Volume=_volume};_player=player;
                try {
                    await File.WriteAllBytesAsync(path,bytes,cancel.Token);cancel.Token.ThrowIfCancellationRequested();
                    var done=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                    using var registration=cancel.Token.Register(()=>done.TrySetCanceled(cancel.Token));
                    player.MediaEnded+=(_,_)=>done.TrySetResult();
                    player.MediaFailed+=(_,_)=>done.TrySetException(new InvalidOperationException("无法播放语音，请检查音频设备。"));
                    player.MediaOpened+=(_,_)=>{if(!cancel.IsCancellationRequested){Changed?.Invoke(chunks.Count==1?"正在朗读 · Edge":$"正在朗读 {i+1}/{chunks.Count} · Edge",false);player.Play();}};
                    player.Open(new Uri(path));await done.Task.WaitAsync(TimeSpan.FromMinutes(6),cancel.Token);
                }finally{player.Close();if(ReferenceEquals(_player,player))_player=null;try{File.Delete(path);}catch(Exception ex)when(ex is IOException or UnauthorizedAccessException){}}
            }
        }catch(OperationCanceledException){if(!cancel.IsCancellationRequested){finalMessage="朗读连接超时，请检查网络后重试。";finalError=true;}}
        catch(Exception ex)when(ex is WebSocketException or IOException or UnauthorizedAccessException or InvalidOperationException or TimeoutException or System.Net.Http.HttpRequestException){finalMessage="Edge 朗读暂不可用，请检查网络、系统时间或音频设备后重试。";finalError=true;}
        finally{if(ReferenceEquals(_current,cancel)){_current=null;Changed?.Invoke(finalMessage,finalError);}}
    }
    public void Dispose()=>Stop();
}
