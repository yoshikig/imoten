/*
 * imoten - i mode.net mail tensou(forward)
 * 
 * Copyright (C) 2010 shoozhoo (http://code.google.com/p/imoten/)
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * 
 */
package immf;

import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.mail.internet.InternetAddress;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.util.EntityUtils;

public class ImodeNetClient implements Closeable{
	private static final Log log = LogFactory.getLog(ImodeNetClient.class);
	private static final String LoginUrl = "https://imode.net/dcm/dfw";
	private static final String JsonUrl = "https://imode.net/imail/aclrs/acgi/";
	private static final String AttachedFileUrl = "https://imode.net/imail/oexaf/acgi/mailfileget";
	private static final String InlineFileUrl = "https://imode.net/imail/oexaf/acgi/mailimgget";
	private static final String SendMailUrl = "https://imode.net/imail/oexaf/acgi/mailsend";
	

	private String name;
	private String pass;
	
	private DefaultHttpClient httpClient;
	private boolean logined;
	
	public ImodeNetClient(String name, String pass){
		this.name = name;
		this.pass = pass;
		
		this.httpClient = new DefaultHttpClient();
		this.httpClient.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.BROWSER_COMPATIBILITY);
	
	}
	
	/**
	 * i mode.netにログインする
	 * @throws LoginException
	 */
	private void login() throws LoginException{
		log.info("# iモード.netにログイン");
		try{
			this.httpClient.getCookieStore().clear();
			HttpPost post = new HttpPost(LoginUrl);
			List<NameValuePair> formparams = new ArrayList<NameValuePair>();
			formparams.add(new BasicNameValuePair("HIDEURL","?WM_AK=https%3a%2f%2fimode.net%2fag&path=%2fimail%2ftop&query="));
			formparams.add(new BasicNameValuePair("LOGIN","WM_LOGIN"));
			formparams.add(new BasicNameValuePair("WM_KEY","0"));
			formparams.add(new BasicNameValuePair("MDCM_UID",this.name));
			formparams.add(new BasicNameValuePair("MDCM_PWD",this.pass));
			UrlEncodedFormEntity entity = null;
			try{
				entity = new UrlEncodedFormEntity(formparams,"UTF-8");
			}catch (Exception e) {}
			post.setHeader("User-Agent","Mozilla/4.0 (compatible;MSIE 7.0; Windows NT 6.0;)");
			post.setEntity(entity);
			try{
				HttpResponse res = this.executeHttp(post);
				if(res==null){
					this.logined = false;
					throw new IOException("Redirect Error");
				}
				if(res.getStatusLine().getStatusCode()!=200){
					this.logined = false;
					throw new IOException("http login response bad status code "+res.getStatusLine().getStatusCode());
				}
				String body = toStringBody(res);
				if(body.indexOf("<title>認証エラー")>0){
					this.logined = false;
					log.info("認証エラー");
					log.debug(body);
					throw new LoginException("認証エラー");
				}
			}finally{
				post.abort();
			}
			
			
			post = new HttpPost(JsonUrl+"login");
			try{
				HttpResponse res = this.requestPost(post, null);
				if(res==null){
					this.logined = false;
					throw new IOException("Login Error");
				}
				if(res.getStatusLine().getStatusCode()!=200){
					this.logined = false;
					throw new IOException("http login2 response bad status code "+res.getStatusLine().getStatusCode());
				}
				this.logined = true;
			}finally{
				post.abort();
			}
		}catch (Exception e) {
			throw new LoginException("Docomo i mode.net Login Error.",e);
		}
	}
	
	/**
	 * 
	 * @param folderId 取得するメールIDの入っているメールボックスのフォルダIDを指定する(0:受信ボックス？)
	 * @return メールIDのリスト
	 * @throws IOException
	 * @throws LoginException
	 */
	@SuppressWarnings("unchecked")
	public synchronized List<String> getMailIdList(int folderId) throws IOException,LoginException{
		log.info("# メールIDリストを取得  フォルダID:"+folderId);
		HttpPost post = null;
		int retry=0;
		do{
			try{
				post = new HttpPost(JsonUrl+"mailidlist");
				HttpResponse res = this.requestPost(post,null);
				if(!isJson(res) && retry==0){
					toStringBody(res);
					post.abort();
					retry++;
					this.login();
					log.info("# ログイン処理完了で再度メールIDリストを取得");
					continue;
				}
				this.logined = true;
				JSONObject json = JSONObject.fromObject(toStringBody(res));
				//log.debug(json.toString(2));
				JSONArray array = json.getJSONObject("data").getJSONArray("folderList");
				for(int i=0; i<array.size(); i++){
					json = array.getJSONObject(i);
					if(json.getInt("folderId")!=folderId){
						continue;
					}
					List<String> r = new ArrayList<String>(JSONArray.toCollection(json.getJSONArray("mailIdList"),String.class));
					Collections.sort(r);
					Collections.reverse(r);
					return r;
				}
				return new ArrayList<String>();
			}finally{
				post.abort();
			}
		}while(retry<=1);
		return new ArrayList<String>();
	}
	
	public synchronized ImodeMail getMail(int folderId, String mailId) throws IOException{
		log.info("# メール情報を取得 フォルダID:"+folderId+" / メールID:"+mailId);
		List<NameValuePair> formparams = new ArrayList<NameValuePair>();
		formparams.add(new BasicNameValuePair("folder.id",Integer.toString(folderId)));
		formparams.add(new BasicNameValuePair("folder.mail.id",mailId));
		
		HttpPost post = null;
		JSONObject json = null;
		try{
			post = new HttpPost(JsonUrl+"maildetail");
			HttpResponse res = this.requestPost(post,formparams);
			json = JSONObject.fromObject(toStringBody(res));
		}finally{
			post.abort();
		}
		//log.debug(json.toString(2));
		
		ImodeMail r = new ImodeMail();
		String myAddr = json.getJSONObject("common").getString("myAddr");
		r.setMyAddr(myAddr);
		json = json.getJSONObject("data").getJSONObject("previewMail");
		r.setFolderId(folderId);
		r.setMailId(mailId);
		r.setSubject(json.getString("subject"));
		r.setTime(json.getString("time"));
		r.setDecomeFlg(json.getInt("decomeFlg")!=0);
		r.setRecvType(json.getInt("recvType"));
		r.setBody(json.getString("body"));
		
		log.info("From     "+r.getFromAddr());
		log.info("Subject  "+r.getSubject());
		log.info("Time     "+r.getTime());
		log.info("DecoFlag "+r.isDecomeFlg());
		log.info("RcvType  "+r.getRecvType());
		
		// メールアドレス
		JSONArray addrs = json.getJSONArray("previewInfo");
		List<String> tolist = new ArrayList<String>();
		List<String> cclist = new ArrayList<String>();
		for(int i=0; i<addrs.size(); i++){
			JSONObject addrJson = addrs.getJSONObject(i);
			int type = addrJson.getInt("type");
			String addr = addrJson.getString("mladdr");
			if(type==0){
				r.setFromAddr(addr);
				log.info("From "+addr);
			}else if(type==1){
				tolist.add(addr);
				log.info("To   "+addr);
			}else if(type==2){
				cclist.add(addr);
				log.info("Cc   "+addr);
			}
		}
		r.setToAddrList(tolist);
		r.setCcAddrList(cclist);
		
		// 添付ファイル
		List<AttachedFile> attache = new ArrayList<AttachedFile>();
		List<AttachedFile> inline = new ArrayList<AttachedFile>();
		JSONArray attaches = json.getJSONArray("attachmentFile");
		log.info("添付ファイル "+attaches.size());
		for(int i=0; i<attaches.size(); i++){
			JSONObject attacheJson = attaches.getJSONArray(i).getJSONObject(0);
			AttachedFile f = this.getAttachedFile(AttachType.Attach, folderId, mailId, attacheJson.getString("id"));
			attache.add(f);
		}
		
		attaches = json.getJSONArray("inlineInfo");
		log.info("添付ファイル(インライン) "+attaches.size());
		for(int i=0; i<attaches.size(); i++){
			JSONObject inlineJson = attaches.getJSONObject(i);
			AttachedFile f = this.getAttachedFile(AttachType.Inline, folderId, mailId, inlineJson.getString("id"));
			inline.add(f);
		}
		
		r.setAttachFileList(attache);
		r.setInlineFileList(inline);
		return r;
	}
	
	private enum AttachType {Attach,Inline};
	/**
	 * 添付ファイルをダウンロードする
	 * 
	 * @param mailId
	 * @param attacheId
	 * @return
	 */
	private AttachedFile getAttachedFile(AttachType type, int folderId, String mailId, String fileId) throws IOException{
		log.debug("# 添付ファイルのダウンロード "+type+"/"+mailId+"/"+fileId);
		
		List<NameValuePair> formparams = new ArrayList<NameValuePair>();
		formparams.add(new BasicNameValuePair("folder.id",Integer.toString(folderId)));
		formparams.add(new BasicNameValuePair("folder.mail.id",mailId));
		if(type==AttachType.Attach){
			formparams.add(new BasicNameValuePair("folder.attach.id", fileId));
		}else{
			formparams.add(new BasicNameValuePair("folder.mail.img.id", fileId));
		}
		formparams.add(new BasicNameValuePair("cdflg","1"));
		
		String pwsp = "";
		for (Cookie c : this.httpClient.getCookieStore().getCookies()) {
			if(c.getName().equalsIgnoreCase("pwsp")){
				String val = c.getValue();
				pwsp = val.substring(val.length()-32);
			}
		}
		
		HttpPost post = null;
		try{
			if(type==AttachType.Attach){
				post = new HttpPost(AttachedFileUrl+"?pwsp="+pwsp);
			}else{
				post = new HttpPost(InlineFileUrl+"?pwsp="+pwsp);
			}
			HttpResponse res = this.requestPost(post, formparams);

			AttachedFile file = new AttachedFile();
			file.setFolderId(folderId);
			file.setMailId(mailId);
			file.setId(fileId);
			Header h = res.getFirstHeader("Content-Disposition");
			HeaderElement[] hes = h.getElements();
			for (HeaderElement he : hes) {
				NameValuePair nvp = he.getParameterByName("filename");
				if(nvp!=null){
					file.setFilename(URLDecoder.decode(nvp.getValue(),"UTF-8"));
				}
			}

			HttpEntity entity = res.getEntity();
			file.setContentType(entity.getContentType().getValue());
			file.setData(EntityUtils.toByteArray(entity));
			log.info("ファイル名   "+file.getFilename());
			log.info("Content-type "+file.getContentType());
			log.info("サイズ       "+file.getData().length);
			return file;
		}finally{
			post.abort();
		}
	}
	
	/**
	 * imode.netの送信フォールからメールを送信
	 * 
	 * @param mail
	 * @return
	 * @throws IOException
	 */
	public synchronized void sendMail(SenderMail mail) throws IOException{
		if(!this.logined){
			log.warn("iモード.netにログインできていません。");
			throw new IOException("imode.net nologin");
		}
		MultipartEntity multi = new MultipartEntity();
		try{
			multi.addPart("fonlder.id", new StringBody("0",Charset.forName("UTF-8")));
			multi.addPart("folder.mail.type", new StringBody("0",Charset.forName("UTF-8")));
			
			// 送信先
			int recipient = 0;
			for (InternetAddress ia : mail.getTo()) {
				multi.addPart("folder.mail.addrinfo("+recipient+").mladdr", new StringBody(ia.getAddress(),Charset.forName("UTF-8")));
				multi.addPart("folder.mail.addrinfo("+recipient+").type", new StringBody("1",Charset.forName("UTF-8")));
				recipient++;
			}
			for (InternetAddress ia : mail.getCc()) {
				multi.addPart("folder.mail.addrinfo("+recipient+").mladdr", new StringBody(ia.getAddress(),Charset.forName("UTF-8")));
				multi.addPart("folder.mail.addrinfo("+recipient+").type", new StringBody("2",Charset.forName("UTF-8")));
				recipient++;
			}
			for (InternetAddress ia : mail.getBcc()) {
				multi.addPart("folder.mail.addrinfo("+recipient+").mladdr", new StringBody(ia.getAddress(),Charset.forName("UTF-8")));
				multi.addPart("folder.mail.addrinfo("+recipient+").type", new StringBody("3",Charset.forName("UTF-8")));
				recipient++;
			}
			if(recipient>=5){
				throw new IOException("Too Much Recipient");
			}
			
			// 題名
			multi.addPart("folder.mail.subject", new StringBody(Util.reverseReplaceUnicodeMapping(mail.getSubject()),Charset.forName("UTF-8")));
			
			// 内容
			String body = Util.reverseReplaceUnicodeMapping(mail.getContent());
			// imode.netの制限 本文は10000Bytes以内で
			if(body.getBytes().length>10000){
				// 文字数かbyte数かわからないのでとりあえずbyte数で制限
				log.warn("本文のサイズが大きすぎます。最大10000byte");
				throw new IOException("Too Big Message Body. Max 10000 byte.");
			}
			System.err.println("body "+body);
			multi.addPart("folder.mail.data", new StringBody(body,Charset.forName("UTF-8")));

			// よくわかんない絵文字情報
			multi.addPart("iemoji(0).id", new StringBody(Character.toString((char)0xe709),Charset.forName("UTF-8")));			// e709 = UTF-8でee89c9の値
			multi.addPart("iemoji(1).id", new StringBody(Character.toString((char)0xe6f0),Charset.forName("UTF-8")));			// e6f0 = UTF-8でee9bb0の値
			
			multi.addPart("reqtype", new StringBody("0",Charset.forName("UTF-8")));

			HttpPost post = new HttpPost(SendMailUrl);
			try{
				addDumyHeader(post);
				post.setEntity(multi);

				HttpResponse res = this.executeHttp(post);
				if(!isJson(res)){
					log.warn("応答がJSON形式ではありません。");
					throw new IOException("Bad response");
				}
				JSONObject json = JSONObject.fromObject(toStringBody(res));
				String result = json.getJSONObject("common").getString("result");
				if(!result.equals("PW1000")){
					log.debug(json.toString(2));
					throw new IOException("Bad response "+result);
				}
			}finally{
				post.abort();
			}
		}catch (UnsupportedEncodingException e) {
			log.fatal(e);
		}
	}

	private static boolean isJson(HttpResponse res){
		if(res==null){
			return false;
		}
		Header h = res.getFirstHeader("Content-type");
		if(h.getValue().toUpperCase().indexOf("JSON")>=0){
			return true;
		}else{
			return false;
		}
	}
	
	private static String toStringBody(HttpResponse res) throws IOException{
		return EntityUtils.toString(res.getEntity(),"Shift_JIS");
	}
	
	/**
	 * POSTは自動でリダイレクトされないので手動でリダイレクト
	 * @param req
	 * @return
	 * @throws IOException
	 */
	private HttpResponse executeHttp(HttpRequestBase req) throws IOException{
		try{
			for(int i=0; i<4; i++){
				HttpResponse res = this.httpClient.execute(req);
				int status = res.getStatusLine().getStatusCode();
				if(300<=status && status<=399){
					req.abort();
					
					URI location = httpClient.getRedirectHandler().getLocationURI(res, new BasicHttpContext());
					
					//System.out.println("Redirect "+location);
					req = new HttpGet(location);
					req.setHeader("User-Agent","Mozilla/4.0 (compatible;MSIE 7.0; Windows NT 6.0;)");
				}else{
					return res;
				}
			}
		}catch (Exception e) {e.printStackTrace();}
		return null;
	}

	/*
	 * IEがAjaxで行うリクエストを行う
	 */
	private HttpResponse requestPost(HttpPost post,List<NameValuePair> formparams) throws IOException{
		addDumyHeader(post);
		
		if(formparams!=null){
			UrlEncodedFormEntity entity = null;
			try{
				entity = new UrlEncodedFormEntity(formparams,"UTF-8");
			}catch (Exception e) {}
			post.setEntity(entity);
		}else{
			post.setHeader("Content-Type","application/x-www-form-urlencoded");
		}
		
		return this.executeHttp(post);
	}
	
	private static void addDumyHeader(HttpEntityEnclosingRequestBase req){
		req.setHeader("Accept", "*/*");
		req.setHeader("Accept-Encoding","gzip, deflate");
		req.setHeader("Cache-Control", "no-cache");
		req.setHeader("User-Agent","Mozilla/4.0 (compatible;MSIE 7.0; Windows NT 6.0;)");
		req.setHeader("x-pw-service","PCMAIL/1.0");
		req.setHeader("Referer","https://imode.net/imail/aclrs/ahtm/index.html");
	}
	
	public void setSoTimeout(int millisec){
		this.httpClient.getParams().setIntParameter("http.socket.timeout", millisec);
	}
	
	public void setConnTimeout(int millisec){
		this.httpClient.getParams().setIntParameter("http.connection.timeout", millisec);
	}
	
	public void clearCookie(){
		this.httpClient.getCookieStore().clear();
	}
	
	/**
	 * クッキーを取得
	 * @return
	 */
	public List<Cookie> getCookies(){
		return this.httpClient.getCookieStore().getCookies();
	}
	
	/**
	 * クッキーを追加する。
	 * 前のログインから時間が経過していなければ、以前使用したクッキーを再設定してログインが省略できる。
	 * @param cookie
	 */
	public void addCookie(Cookie cookie){
		this.httpClient.getCookieStore().addCookie(cookie);
	}
	
	public void close(){
		try{
			this.httpClient.getConnectionManager().shutdown();
		}catch (Exception e) {}
	}
}
