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

import immf.google.contact.GoogleContactsAccessor;
import immf.growl.GrowlNotifier;
import immf.growl.concrete.NMAClient;
import immf.growl.concrete.ProwlClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.cookie.Cookie;

public class ServerMain {
	public static final String Version = "imoten (imode.net mail tenson) ver. 1.1.37";
	private static final Log log = LogFactory.getLog(ServerMain.class);

	public ImodeNetClient client;
	public Config conf;
	public StatusManager status;
	public SkypeForwarder skypeForwarder;
	public ImKayacNotifier imKayacNotifier;
	public AppNotifications appNotifications;
	public GrowlNotifier prowlNotifier;
	public GrowlNotifier nmaNotifier;
	public Map<Config, ForwardMailPicker> forwarders = new HashMap<Config, ForwardMailPicker>();
	public Map<Config, List<String>> ignoreDomainsMap = new HashMap<Config, List<String>>();
	private SendMailPicker spicker;
	private int numForwardSite;

	public ServerMain(File conffile){
		System.out.println("StartUp ["+Version+"]");
		log.info("StartUp ["+Version+"]");
		this.setShutdownHook();
		this.verCheck();
		try{
			log.info("Load Config file "+conffile.getAbsolutePath());
			FileInputStream is = new FileInputStream(conffile);
			this.conf = new Config(is);
			this.numForwardSite = conf.countForwardSite();
			if(numForwardSite>1){
				log.info("複数の転送設定があります:"+numForwardSite);
			}

		}catch (Exception e) {
			log.fatal("Config Error. 設定ファイルに問題があります。",e);
			e.printStackTrace();
			System.exit(1);
		}

		// cookieと最後に転送したメールID
		File stFile = new File(conf.getStatusFile());
		log.info("Load Status file "+stFile.getAbsolutePath());
		this.status = new StatusManager(stFile);
		try{
			this.status.load();
		}catch (Exception e) {
			// ステータスファイルが無い場合
			log.info("Status File load error. "+e.getMessage());
			log.info("Statusファイルが作成されます。");
		}
		log.info("Loaded LastMailID="+this.status.getLastMailId());

		this.client = new ImodeNetClient(this.conf.getDocomoId(),conf.getDocomoPasswd());
		this.client.setConnTimeout(this.conf.getHttpConnectTimeoutSec()*1000);
		this.client.setSoTimeout(this.conf.getHttpSoTimeoutSec()*1000);
		this.client.setMailAddrCharset(this.conf.getMailEncode());
		this.client.setCsvAddressBook(this.conf.getCsvAddressFile());
		//GoogleContactsAccesorの初期化
		GoogleContactsAccessor.initialize(this.conf.getGmailId(), this.conf.getGmailPasswd());
		this.client.setVcAddressBook(this.conf.getVcAddressFile());

		CharacterConverter subjectCharConv = new CharacterConverter();
		for (String file : conf.getForwardSubjectCharConvertFile()){
			try {
				subjectCharConv.load(new File(file));
			} catch (Exception e) {
				log.error("文字変換表("+file+")が読み込めませんでした。",e);
			}
		}
		ImodeForwardMail.setSubjectCharConv(subjectCharConv);
		SpmodeForwardMail.setSubjectCharConv(subjectCharConv);

		if(conf.isForwardAddGoomojiSubject()){
			CharacterConverter goomojiSubjectCharConv = new CharacterConverter();
			if(conf.getForwardGoogleCharConvertFile()!=null) {
				try {
					goomojiSubjectCharConv.load(new File(conf.getForwardGoogleCharConvertFile()));
				} catch (Exception e) {
					log.error("文字変換表("+conf.getForwardGoogleCharConvertFile()+")が読み込めませんでした。",e);
				}
			}
			ImodeForwardMail.setGoomojiSubjectCharConv(goomojiSubjectCharConv);
			SpmodeForwardMail.setGoomojiSubjectCharConv(goomojiSubjectCharConv);
		}

		StringConverter strConv = new StringConverter();
		if(conf.getForwardStringConvertFile()!=null) {
			try {
				strConv.load(new File(conf.getForwardStringConvertFile()));
			} catch (Exception e) {
				log.error("文字列変換表("+conf.getForwardStringConvertFile()+")が読み込めませんでした。",e);
			}
		}
		ImodeForwardMail.setStrConv(strConv);
		SpmodeForwardMail.setStrConv(strConv);

		// 転送抑止ドメインリスト読み込み
		this.loadIgnoreDomainList(this.conf, 1);

		try{
			// 前回のcookie
			if(this.conf.isSaveCookie()){
				log.info("Load cookie");
				for (Cookie	cookie : this.status.getCookies()) {
					this.client.addCookie(cookie);
				}
			}
		}catch (Exception e) {}

		// メール送信
		spicker = new SendMailPicker(conf, this, this.client, this.status);
		new SendMailBridge(conf, this.client, this.spicker, this.status);

		// メール転送
		Config forwardConf = this.conf;
		ForwardMailPicker fpicker = new ForwardMailPicker(forwardConf, this);
		forwarders.put(forwardConf, fpicker);
		
		for(int i=2; i<=numForwardSite; i++){
			try{
				log.info("Load Config file["+i+"] "+conffile.getAbsolutePath());
				FileInputStream is = new FileInputStream(conffile);
				is = new FileInputStream(conffile);
				forwardConf = new Config(is, i);
				fpicker = new ForwardMailPicker(forwardConf, this);
				forwarders.put(forwardConf, fpicker);

				// 転送抑止ドメインリスト読み込み
				this.loadIgnoreDomainList(forwardConf, i);

			}catch (Exception e) {
				log.fatal("Config Error. 設定ファイルに問題があります。",e);
				e.printStackTrace();
				System.exit(1);
			}
		}

		// skype
		this.skypeForwarder = new SkypeForwarder(conf.getForwardSkypeChat(),conf.getForwardSkypeSms(),conf);

		// im.kayac.com
		this.imKayacNotifier = new ImKayacNotifier(this.conf);

		// appnotifications
		this.appNotifications = new AppNotifications(this.conf, this.status);

		// Growl APIs
		// for Prowl
		this.prowlNotifier = GrowlNotifier.getInstance(ProwlClient.getInstance(), this.conf);

		// for Notify My Android
		this.nmaNotifier = GrowlNotifier.getInstance(NMAClient.getInstance(), this.conf);

		// iモードメール着信監視
		if(this.conf.getDocomoId()!=null&&conf.getDocomoPasswd()!=null){
			ImodeCheckMail imodeChecker = new ImodeCheckMail(this);
			Thread ti = new Thread(imodeChecker);
			ti.setName("ImodeChecker");
			ti.setDaemon(true);
			ti.start();
		}
		
		// spモードメール着信監視
		if(this.conf.getSpmodeMailUser()!=null&&conf.getSpmodeMailPasswd()!=null){
			SpmodeCheckMail spmodeChecker = new SpmodeCheckMail(this);
			Thread ts = new Thread(spmodeChecker);
			ts.setName("SpmodeChecker");
			ts.setDaemon(true);
			ts.start();
		}
		
		while(true){
			try{
				// 負荷をかけないように
				Thread.sleep(1000000);
			}catch (Exception e) {}
		}
	}

	private void verCheck(){
		String verndor = System.getProperty("java.vendor");
		String version = System.getProperty("java.version");
		log.info("Java vendor  "+verndor);
		log.info("Java version "+version);
		log.info("defaultCharset "+Charset.defaultCharset());
		try{
			String[] v = version.split("\\.");
			if(Integer.parseInt(v[0])>=2 || Integer.parseInt(v[1])>=6){
				return;
			}else{
				log.warn("注意 動作にはJava ver 1.6 以上が必要となります。");
			}
		}catch (Exception e) {
			log.warn(e.getMessage());
		}
	}

	/*
	 * 任意のメッセージを直接通知する。
	 */
	public void notify(String message){
		// XXX skypeForwarder?
		// XXX imKayacNotifier?
		this.appNotifications.push(message);
	}

	/*
	 * 転送抑止ドメインリスト作成
	 */
	private void loadIgnoreDomainList(Config conf, int index) {
		List<String> ignoreDomains = new ArrayList<String>();
		String ignoreDomainTxt = conf.getIgnoreDomainFile();
		File ignoreDomainFile = new File(ignoreDomainTxt);
		if(!ignoreDomainFile.exists()){
			log.info("# 転送抑止ドメインリスト["+index+"]("+ignoreDomainTxt+")は存在しません。");
			this.ignoreDomainsMap.put(conf, ignoreDomains);
			return;
		}
		BufferedReader br = null;
		FileReader fr = null;
		try{
			fr = new FileReader(ignoreDomainFile);
			br = new BufferedReader(fr);
			//int id = 0;

			String line = null;
			while((line = br.readLine()) != null){
				//id++;
				try{
					if(line.startsWith("#")){
						continue;
					}
					if(!line.contains(".")){
						continue;
					}
					ignoreDomains.add(line);

				}catch (Exception e) {
					log.warn("loadIgnoreDomainList error.",e);
				}
			}
			br.close();
		}catch (Exception e){
			log.warn("loadIgnoreDomainList "+ignoreDomainTxt+" error.",e);

		}finally{
			Util.safeclose(br);
			Util.safeclose(fr);
			String ignores = "";
			for (String domain : ignoreDomains) {
				ignores += domain + " ";
			}
			log.info("# 転送抑止ドメイン["+index+"]:"+ignores);
			this.ignoreDomainsMap.put(conf, ignoreDomains);
		}
	}

	/*
	 * 停止時にログを出力
	 */
	private void setShutdownHook(){
		Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
        		System.out.println("Shutdown ["+Version+"]");
        		log.info("Shutdown ["+Version+"]");
            }
        });;

	}

	public static void main(String[] args){
		try{
			String confFile = Config.ConfFile;
			if(args.length>0){
				confFile = args[0];
			}
			new ServerMain(new File(confFile));
		}catch (Exception e) {
			e.printStackTrace();
			log.fatal("Startup Error.",e);
		}
	}
}

