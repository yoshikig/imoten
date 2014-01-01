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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.cookie.BasicClientCookie;

public class StatusManager {
	private static final Log log = LogFactory.getLog(StatusManager.class);
	private File f;

	private List<Cookie> cookies;
	private String lastMailId;
	private String lastSpMsgId;
	private String lastSpMsgUid;
	private String pushCredentials;
	private String needConnect;

	public StatusManager(File f){
		this.f = f;
		this.cookies = new ArrayList<Cookie>();
	}

	public void load() throws IOException{
		Properties prop = new Properties();
		FileInputStream fis = null;
		try{
			fis = new FileInputStream(this.f);
			prop.load(fis);
			this.lastMailId = prop.getProperty("lastmailid");
			this.lastSpMsgId = prop.getProperty("lastspmsgId");
			this.lastSpMsgUid = prop.getProperty("lastspmsgUid");
			this.pushCredentials = prop.getProperty("push_credentials");
			String nc = prop.getProperty("needconnect");
			if(this.needConnect==null){
				this.needConnect = nc;
			}
			if(this.needConnect!=null && !this.needConnect.equals("1")){
				this.needConnect = nc;
			}
			Enumeration<Object> enu = prop.keys();
			List<Cookie> list = new ArrayList<Cookie>();
			while(enu.hasMoreElements()){
				String key = (String)enu.nextElement();

				if(key.startsWith("cookie_")){
					String cookieName = key.substring(7);
					String val = prop.getProperty(key);
					String[] params = val.split(";");
					BasicClientCookie c = new BasicClientCookie(cookieName, params[0]);
					for(int i=1; i<params.length; i++){
						String[] nameval = params[i].split("=");
						if(nameval[0].equalsIgnoreCase("path")){
							c.setPath(nameval[1]);
						}else if(nameval[0].equalsIgnoreCase("domain")){
							c.setDomain(nameval[1]);
						}
					}
					c.setSecure(true);
					log.debug("Load Cookie ["+c.getName()+"]=["+c.getValue()+"]");
					list.add(c);
				}
			}
			this.cookies = list;
		}finally{
			Util.safeclose(fis);
		}
	}
	public String getLastMailId(){
		return lastMailId;
	}
	public void setLastMailId(String s){
		this.lastMailId = s;
	}
	public String getLastSpMsgId(){
		return lastSpMsgId;
	}
	public void setLastSpMsgId(String s){
		this.lastSpMsgId = s;
	}
	public String getLastSpMsgUid(){
		return lastSpMsgUid;
	}
	public void setLastSpMsgUid(String s){
		this.lastSpMsgUid = s;
	}
	public String getPushCredentials(){
		return pushCredentials;
	}
	public void setPushCredentials(String s){
		this.pushCredentials = s;
	}
	public boolean needConnect(){
		if(this.needConnect==null)
			return true;
		if(this.needConnect.equals("0")){
			return false;
		}else{
			return true;
		}
	}
	public void resetNeedConnect(){
		if(this.needConnect!=null)
			this.needConnect = "0";
	}
	public void setNeedConnect(){
		if(this.needConnect!=null)
			this.needConnect = "1";
	}
	public List<Cookie> getCookies(){
		return new ArrayList<Cookie>(this.cookies);
	}
	public void setCookies(List<Cookie> cookies){
		this.cookies = new ArrayList<Cookie>(cookies);
	}

	public synchronized void save() throws IOException{
		Properties prop = new Properties();
		for (Cookie cookie : this.cookies) {
			prop.setProperty("cookie_"+cookie.getName(), cookie.getValue()+";path="+cookie.getPath()+";domain="+cookie.getDomain());
		}
		if(this.lastMailId!=null){
			prop.setProperty("lastmailid", this.lastMailId);
		}
		if(this.lastSpMsgId!=null){
			prop.setProperty("lastspmsgId", this.lastSpMsgId);
		}
		if(this.lastSpMsgUid!=null){
			prop.setProperty("lastspmsgUid", this.lastSpMsgUid);
		}
		if(this.pushCredentials!=null && this.pushCredentials.length()>0){
			prop.setProperty("push_credentials", this.pushCredentials);
		}
		if(this.needConnect!=null){
			prop.setProperty("needconnect", this.needConnect);
		}
		FileOutputStream fos = null;
		try{
			fos = new FileOutputStream(this.f);
			prop.store(fos, "IMMF cookie info.");
		}finally{
			Util.safeclose(fos);
		}
	}
}
