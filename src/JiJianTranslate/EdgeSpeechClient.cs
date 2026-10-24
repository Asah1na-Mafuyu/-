// Edge online speech protocol; see https://github.com/rany2/edge-tts (LGPL-3.0).
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace JiJianTranslate;

internal static class EdgeSpeechClient
{
    internal const string UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    internal static string Voice(string language) => language switch {
        "简体中文"=>"zh-CN-XiaoxiaoNeural", "繁體中文"=>"zh-TW-HsiaoChenNeural", "English"=>"en-US-JennyNeural",
        "日本語"=>"ja-JP-NanamiNeural", "한국어"=>"ko-KR-SunHiNeural", "Français"=>"fr-FR-DeniseNeural",
        "Deutsch"=>"de-DE-KatjaNeural", "Español"=>"es-ES-ElviraNeural", "Русский"=>"ru-RU-SvetlanaNeural",
        _=>throw new InvalidOperationException("此语言暂未配置朗读声音。") };
    internal static List<string> Chunks(string text)
    {
        var clean=new StringBuilder();
        for(int i=0;i<text.Length;i++) { char c=text[i];if(char.IsHighSurrogate(c)){if(i+1<text.Length&&char.IsLowSurrogate(text[i+1])){clean.Append(c).Append(text[++i]);}else clean.Append(' ');}else if(char.IsLowSurrogate(c)||c==0xfffe||c==0xffff||c<32&&c!='\n'&&c!='\r'&&c!='\t')clean.Append(' ');else clean.Append(c); }
        string value=clean.ToString().Trim();var parts=new List<string>();
        for(int start=0;start<value.Length;) {
            int end=Math.Min(start+900,value.Length);
            if(end<value.Length){if(char.IsHighSurrogate(value[end-1]))--end;for(int i=end-1;i>start+300;i--)if("。！？.!?\n；; ".Contains(value[i])){end=i+1;break;}}
            parts.Add(value.Substring(start,end-start));start=end;
        }
        return parts;
    }
    internal static string Ssml(string text,string voice,int rate)
    {
        string escaped=text.Replace("&","&amp;").Replace("<","&lt;").Replace(">","&gt;").Replace("\"","&quot;").Replace("'","&apos;");
        int speed=rate is -20 or 20?rate:0;
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='"+voice[..5]+"'><voice name='"+voice+"'><prosody pitch='+0Hz' rate='"+(speed>=0?"+":"")+speed.ToString(CultureInfo.InvariantCulture)+"%' volume='+0%'>"+escaped+"</prosody></voice></speak>";
    }
    internal static byte[] AudioPayload(byte[] message)
    {
        if(message.Length<2)throw new InvalidDataException("语音响应不完整。");int length=(message[0]<<8)|message[1];
        if(length>message.Length-2)throw new InvalidDataException("语音响应头不完整。");
        string header=Encoding.UTF8.GetString(message,2,length);
        if(!header.Contains("Path:audio",StringComparison.OrdinalIgnoreCase))throw new InvalidDataException("语音响应格式无法识别。");
        return message.AsSpan(2+length).ToArray();
    }
    internal static async Task<byte[]> SynthesizeAsync(string text,string language,int rate,CancellationToken token)
    {
        using var timeout=CancellationTokenSource.CreateLinkedTokenSource(token);timeout.CancelAfter(TimeSpan.FromSeconds(45));var ct=timeout.Token;
        using var socket=new ClientWebSocket();socket.Options.SetRequestHeader("User-Agent",UserAgent);
        socket.Options.SetRequestHeader("Origin","chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold");socket.Options.SetRequestHeader("Cache-Control","no-cache");socket.Options.SetRequestHeader("Pragma","no-cache");
        socket.Options.SetRequestHeader("Cookie","muid="+Guid.NewGuid().ToString("N").ToUpperInvariant()+";");
        string id=Guid.NewGuid().ToString("N");var uri=new Uri("wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken="+EdgeToken.ClientToken+"&ConnectionId="+id+"&Sec-MS-GEC="+EdgeToken.Generate(DateTimeOffset.UtcNow.ToUnixTimeSeconds())+"&Sec-MS-GEC-Version="+EdgeToken.Version);
        await socket.ConnectAsync(uri,ct);
        string stamp=DateTime.UtcNow.ToString("ddd MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",CultureInfo.InvariantCulture);
        string config="X-Timestamp:"+stamp+"\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
        await socket.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes(config)),WebSocketMessageType.Text,true,ct);
        string request="X-RequestId:"+id+"\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:"+stamp+"Z\r\nPath:ssml\r\n\r\n"+Ssml(text,Voice(language),rate);
        await socket.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes(request)),WebSocketMessageType.Text,true,ct);
        using var audio=new MemoryStream();var buffer=new byte[16384];
        while(true) {
            using var message=new MemoryStream();WebSocketReceiveResult received;
            do {received=await socket.ReceiveAsync(new ArraySegment<byte>(buffer),ct);if(received.MessageType==WebSocketMessageType.Close)throw new InvalidDataException("Edge 提前结束了语音连接。");message.Write(buffer,0,received.Count);if(message.Length>1024*1024)throw new InvalidDataException("语音响应过大。");}while(!received.EndOfMessage);
            byte[] data=message.ToArray();
            if(received.MessageType==WebSocketMessageType.Binary){byte[] bytes=AudioPayload(data);audio.Write(bytes);if(audio.Length>4*1024*1024)throw new InvalidDataException("本段语音过长，请缩短译文。");}
            else if(Encoding.UTF8.GetString(data).Contains("Path:turn.end",StringComparison.OrdinalIgnoreCase))break;
        }
        if(audio.Length<100)throw new InvalidDataException("Edge 未返回语音，请稍后再试。");
        socket.Abort();return audio.ToArray();
    }
}
