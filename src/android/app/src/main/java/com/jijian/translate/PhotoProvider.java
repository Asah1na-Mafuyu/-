package com.jijian.translate;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/** Grants the selected camera app access to a single private, temporary photo. */
public final class PhotoProvider extends ContentProvider {
    static final String AUTHORITY="com.jijian.translate.camera";
    private static final String NAME="photo-[0-9a-f]{32}\\.jpg";
    @Override public boolean onCreate(){return true;}
    static Uri create(Context context)throws IOException{
        File folder=new File(context.getCacheDir(),"camera");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法创建拍照缓存。");
        File[] old=folder.listFiles();if(old!=null)for(File file:old)if(file.getName().matches(NAME)&&file.lastModified()<System.currentTimeMillis()-86400000L)file.delete();
        String name="photo-"+UUID.randomUUID().toString().replace("-","")+".jpg";File file=new File(folder,name);if(!file.createNewFile())throw new IOException("无法准备照片。");
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build();
    }
    static File file(Context context,Uri uri)throws FileNotFoundException{
        if(uri==null||!"content".equals(uri.getScheme())||!AUTHORITY.equals(uri.getAuthority())||uri.getPathSegments().size()!=1||uri.getQuery()!=null||uri.getFragment()!=null)throw new FileNotFoundException("照片地址无效。");
        String name=uri.getLastPathSegment();if(name==null||!name.matches(NAME))throw new FileNotFoundException("照片地址无效。");
        try{File folder=new File(context.getCacheDir(),"camera").getCanonicalFile();File target=new File(folder,name).getCanonicalFile();if(!folder.equals(target.getParentFile()))throw new IOException();return target;}catch(IOException e){throw new FileNotFoundException("照片地址无效。");}
    }
    static void discard(Context context,Uri uri){if(uri==null)return;try{context.revokeUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION|android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);file(context,uri).delete();}catch(Exception ignored){}}
    @Override public String getType(Uri uri){try{file(getContext(),uri);return "image/jpeg";}catch(FileNotFoundException e){return null;}}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return ParcelFileDescriptor.open(file(getContext(),uri),ParcelFileDescriptor.parseMode(mode));}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        try{File file=file(getContext(),uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=file.getName();else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=file.length();}cursor.addRow(values);return cursor;}catch(FileNotFoundException e){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
    @Override public int delete(Uri uri,String selection,String[] args){try{return file(getContext(),uri).delete()?1:0;}catch(FileNotFoundException e){return 0;}}
}
