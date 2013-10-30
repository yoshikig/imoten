/*
 * imoten - i mode.net mail tensou(forward)
 *
 * Copyright (C) 2013 ryu aka 508.P905 (http://code.google.com/p/imoten/)
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

import immf.growl.GrowlNotifier;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
//import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SpmodeCheckMail implements Runnable{
	private static final Log log = LogFactory.getLog(ServerMain.class);

	private Config conf;
	private StatusManager status;
	private SkypeForwarder skypeForwarder;
	private ImKayacNotifier imKayacNotifier;
	private GrowlNotifier prowlNotifier;
	private GrowlNotifier nmaNotifier;
	private AppNotifications appNotifications;
	private int numForwardSite;
	private Map<Config, ForwardMailPicker> forwarders = new HashMap<Config, ForwardMailPicker>();
	private Map<Config, List<String>> ignoreDomainsMap = new HashMap<Config, List<String>>();

	private AddressBook addressBook;
	private String csvAddressBook;
	private String vcAddressBook;

	public SpmodeCheckMail(ServerMain server){
		this.conf = server.conf;
		this.status = server.status;
		this.skypeForwarder = server.skypeForwarder;
		this.imKayacNotifier = server.imKayacNotifier;
		this.prowlNotifier = server.prowlNotifier;
		this.nmaNotifier = server.nmaNotifier;
		this.appNotifications = server.appNotifications;
		this.numForwardSite = conf.countForwardSite();
		this.forwarders = server.forwarders;
		this.ignoreDomainsMap = server.ignoreDomainsMap;
		this.csvAddressBook = this.conf.getCsvAddressFile();
		this.vcAddressBook = this.conf.getVcAddressFile();
	}

	public void run() {
		Properties props = new Properties();
		props.setProperty("mail.pop3.host", "mail.spmode.ne.jp");
		props.setProperty("mail.pop3.port", "995");
		props.setProperty("mail.pop3.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		props.setProperty("mail.pop3.socketFactory.fallback", "false");
		props.setProperty("mail.pop3.socketFactory.port", "995");

		// XXX 設定可能にする？
		//props.setProperty("mail.pop3.connectiontimeout", XXX);
		//props.setProperty("mail.pop3.timeout", XXX);

		final String myname = conf.getSpmodeMailUser();
		final String passwd = conf.getSpmodeMailPasswd();

		// 読み込みは初回起動時のみ
		this.loadAddressBook();

		//Date lastUpdate = null;
		while(true){
			/*
			if(lastUpdate != null){
				// 接続フラグを見るためにステータスファイルをチェック
				try{
					this.status.load();
				}catch (Exception e) {}
				long diff = System.currentTimeMillis() - lastUpdate.getTime();
				if(diff < conf.getForceCheckIntervalSec()*1000 && !this.status.needConnect()){
					//接続フラグが立っていなければ次のチェックまで待つ
					try{
						Thread.sleep(conf.getCheckFileIntervalSec()*1000);
					}catch (Exception e) {}
					continue;
				}
			}
			*/

			String thisId = "";
			try{
				Session session = Session.getInstance(props, null);
				session.setDebug(conf.isMailDebugEnable());
				
				Store store = session.getStore("pop3");
				store.connect(myname, passwd);
				
				Folder folder = store.getDefaultFolder();
				folder = folder.getFolder("INBOX");
				if(conf.isSpmodeReadonly()){
					folder.open(Folder.READ_ONLY);
				}else{
					try{
						folder.open(Folder.READ_WRITE);
					}catch(MessagingException me){
						folder.open(Folder.READ_ONLY);
					}
				}

				Message messages[] = folder.getMessages();
				if(StringUtils.isBlank(this.status.getLastSpMsgId())){
					//IDが未設定の時
					Message msg = messages[messages.length-1];
					try {
						thisId = msg.getHeader("Message-ID")[0];
					}catch(MessagingException me){
						// XXX メールヘッダをダンプするコードを入れておくべき。以下同文
						log.error("メールヘッダ取得失敗",me);
					}
				}else{
					String lastId = this.status.getLastSpMsgId();
					//IDが設定されてた時、当該IDのメールを降順(新しいメールから順)に探す
					int start = -1;
					Message msg;
					for (int index = messages.length-1; index >= 0; index--) {
						msg = messages[index];
						try{
							thisId = msg.getHeader("Message-ID")[0];
							//log.info("ID探索:"+index+","+thisId);
							if (thisId.equals(lastId)) {
								start = index;
								break;
							}
						}catch(MessagingException me){
							log.error("メールヘッダ取得失敗",me);
						}
					}
								
					//メールの取得と転送
					log.info("転送するメールIDの数 "+(messages.length - start - 1));
					appNotifications.pushPrepare(0, messages.length - start - 1);
					for (int index = start + 1; index < messages.length; index++) {
						msg = messages[index];
						try{
							thisId = msg.getHeader("Message-ID")[0];
							log.info("メール転送:"+index+","+thisId);
							this.forward(msg, thisId);
						}catch(MessagingException me){
							log.error("メールヘッダ取得失敗",me);
						}
					}
				}
				
				folder.close(false);
				store.close();
			} catch(Exception e) {
				log.error("メールサーバ接続時に例外発生",e);
			}
					
			/*
			// 接続フラグのリセット
			this.status.resetNeedConnect();
			*/

			// status.ini の更新
			if(StringUtils.isBlank(this.status.getLastSpMsgId())){
				this.status.setLastSpMsgId(thisId);
				log.info("LastSpMsgIdが空なので、次のメールから転送を開始します。");
			}
			if(!thisId.isEmpty()){
				String lastId = this.status.getLastSpMsgId();
				if(lastId!=null && !lastId.equals(thisId)){
					this.status.setLastSpMsgId(thisId);
					log.info("LastSpMsgIdを更新しました");
				}
			}
			try{
				this.status.save();
				log.info("statusファイルを保存しました");
			}catch (Exception e) {
				log.error("Status File save Error.",e);
			}
			//lastUpdate = new Date();

			// 次のチェックまで待つ
			try{
				Thread.sleep(conf.getSpmodeCheckIntervalSec()*1000);
			}catch (Exception e) {}
		}

	}
	/*
	 * メールをダウンロードして送信
	 */
	private void forward(Message msg, String mailId){
		String from;
		try {
			from = ((InternetAddress)msg.getFrom()[0]).getAddress();
		} catch (MessagingException e) {
			from = "";
		}
		List<String> ignoreDomains = new ArrayList<String>();
		try{
			// 送信
			for (Map.Entry<Config, ForwardMailPicker> f : forwarders.entrySet()) {
				Config forwardConf = f.getKey();
				int id = forwardConf.getConfigId();

				if(forwardConf.getForwardOnly()!=Config.ForwardOnly.SPmode
						&&forwardConf.getForwardOnly()!=Config.ForwardOnly.BOTH){
					continue;
				}

				//  転送抑止ドメインリストと比較して送信可否判定
				boolean notForward = false;
				ignoreDomains = ignoreDomainsMap.get(forwardConf);
				for (String domain : ignoreDomains) {
					if(from.endsWith(domain)){
						log.info("送信者:"+from+" のメール転送中止["+id+"]");
						notForward = true;
					}
				}
				if(notForward){
					continue;
				}
				SpmodeForwardMail forwardMail = new SpmodeForwardMail(msg, forwardConf, this.addressBook);
				forwardMail.send();
				if(numForwardSite>1){
					log.info("転送処理完了["+id+"]");
				}else{
					log.info("転送処理完了");
				}
			}

		}catch (Exception e) {
			log.error("mail["+mailId+"] forward Error.",e);
			return;
		}

		//  転送抑止ドメインリストと比較してPush送信可否判定
		ignoreDomains = ignoreDomainsMap.get(this.conf);
		for (String domain : ignoreDomains) {
			if(from.endsWith(domain)){
				this.appNotifications.pushError(0);
				return;
			}
		}

		ImodeMail mail = message2imodemail(msg);
		try{
			this.skypeForwarder.forward(mail);
		}catch (Exception e) {
			log.error("mail["+mailId+"] skype forward Error.",e);
			return;
		}

		try{
			this.imKayacNotifier.forward(mail);
		}catch (Exception e) {
			log.error("mail["+mailId+"] im.kayac forward Error.",e);
			return;
		}

		try{
			this.prowlNotifier.forward(mail);
		}catch (Exception e) {
			log.error("mail["+mailId+"] Prowl forward Error.",e);
			return;
		}

		try{
			this.nmaNotifier.forward(mail);
		}catch (Exception e) {
			log.error("mail["+mailId+"] NMA forward Error.",e);
			return;
		}

		try{
			this.appNotifications.push(0, mail);
		}catch (Exception e) {
			this.appNotifications.pushError(0);
			log.error("mail["+mailId+"] AppNotifications push Error.",e);
			return;
		}

		try{
			// 負荷をかけないように
			Thread.sleep(1000);
		}catch (Exception e) {}
	}
	
	// ImodeNetClient.javaより
	/*
	 * アドレス帳情報を読み込む
	 */
	private void loadAddressBook(){
		AddressBook ab = new AddressBook();
		try{
			this.loadCsvAddressBook(ab);
		}catch (Exception e) {
			log.warn("CSVのアドレス帳情報が読み込めませんでした。");
		}
		try{
			this.loadVcAddressBook(ab);
		}catch (Exception e) {
			log.warn("vCardのアドレス帳情報が読み込めませんでした。");
		}

		this.addressBook = ab;
	}
	/*
	 * CSVのアドレス帳情報を読み込む
	 */
	private void loadCsvAddressBook(AddressBook ab) throws IOException{
		if(this.csvAddressBook==null){
			return;
		}
		File csvFile = new File(this.csvAddressBook);
		if(!csvFile.exists()){
			log.info("# CSVアドレス帳ファイル("+this.csvAddressBook+")は存在しません。");
			return;
		}
		log.info("# CSVアドレス帳情報を読み込みます。");
		BufferedReader br = null;
		FileReader fr = null;
		try{
			// デフォルトエンコードで読み込まれる
			// wrapper.confで-Dfile.encoding=UTF-8を指定しているのでUTF-8になる
			fr = new FileReader(csvFile);
			br = new BufferedReader(fr);
			int id = 0;

			String line = null;
			while((line = br.readLine()) != null){
				// フォーマット:
				// メールアドレス,ディスプレイネーム
				id++;
				try{
					String[] field = line.split(",");
					if(field.length < 2){
						continue;
					}
					InternetAddress[] addrs = InternetAddress.parse(field[0]);
					if(addrs.length == 0)
						continue;
					ImodeAddress ia = new ImodeAddress();
					ia.setMailAddress(addrs[0].getAddress());
					ia.setName(field[1]);
					ia.setId(String.valueOf(id));
					ab.addCsvAddr(ia);
					log.debug("ID:"+ia.getId()+" / Name:"+ia.getName()+" / Address:"+ia.getMailAddress());

				}catch (Exception e) {
					log.warn("CSVファイル("+id+"行目)に問題があります["+line+"]");
				}
			}
			br.close();
		}catch (Exception e){
			log.warn("loadCsvAddressBook "+this.csvAddressBook+" error.",e);

		}finally{
			Util.safeclose(br);
			Util.safeclose(fr);
		}
	}

	/*
	 * vCardのアドレス帳情報を読み込む
	 */
	private void loadVcAddressBook(AddressBook ab) throws IOException{
		if(this.vcAddressBook==null){
			return;
		}
		File vcFile = new File(this.vcAddressBook);
		if(!vcFile.exists()){
			log.info("# vCardアドレス帳ファイル("+this.vcAddressBook+")は存在しません。");
			return;
		}
		log.info("# vCardアドレス帳情報を読み込みます。");
		FileInputStream fis = null;
		byte[] vcData = null;
		try{
			fis = new FileInputStream(vcFile);
			vcData = new byte[(int)vcFile.length()];
			fis.read(vcData);
		}catch (Exception e){
			log.warn("loadVcAddressBook "+this.vcAddressBook+" error.",e);
		}finally{
			Util.safeclose(fis);
		}

		int id = 0;
		boolean vcBegin = false;
		String vcName = null;
		String vcEmail = null;

		int lineStart = 0;
		int lineLength = 0;

		for(int i=lineStart; i<=vcFile.length(); i++){
			try{
				if(i == vcFile.length() || vcData[i] == '\n'){
					String line = new String(vcData, lineStart, lineLength);
					int curLineStart = lineStart;
					int curLineLength = lineLength;

					lineStart = i+1;
					lineLength = 0;

					String field[] = line.split(":");
					if(field[0].equalsIgnoreCase("BEGIN")){
						vcBegin = true;
						vcName = null;
						vcEmail = null;
						id++;
					}
					if(vcBegin == true && field[0].equalsIgnoreCase("END")){
						vcBegin = false;

						if (vcName == null || vcEmail == null)
							continue;

						String vcEmails[] = vcEmail.split(";");
						for(int j=0; j<vcEmails.length; j++){
							InternetAddress[] addrs = InternetAddress.parse(vcEmails[j]);
							if(addrs.length == 0)
								continue;
							ImodeAddress ia = new ImodeAddress();
							ia.setMailAddress(addrs[0].getAddress());
							ia.setName(vcName);
							ia.setId(String.valueOf(id+"-"+(j+1)));
							ab.addVcAddr(ia);
							log.debug("ID:"+ia.getId()+" / Name:"+ia.getName()+" / Address:"+ia.getMailAddress());
						}
					}

					if(vcBegin != true || field.length < 2)
						continue;

					String label[] = field[0].split(";");
					String value[] = field[1].split(";");
					// 姓名
					if(label[0].equalsIgnoreCase("FN")){
						vcName = field[1].replace(";"," ").trim();
						if(label.length < 2)
							continue;
						String option[] = label[1].split("=");
						if(option.length < 1 || !option[0].equalsIgnoreCase("CHARSET"))
							continue;
						int valueStart = curLineStart;
						for(int pos=curLineStart; pos<curLineStart+curLineLength; pos++){
							if(vcData[pos] == ':'){
								valueStart = pos+1;
								break;
							}
						}
						vcName = new String(vcData, valueStart, curLineLength-(valueStart-curLineStart), option[1]).replace(";"," ").trim();

					}
					// EMAIL
					if(label[0].equalsIgnoreCase("EMAIL")){
						if(vcEmail == null)
							vcEmail = value[0];
						else
							vcEmail = vcEmail + ';' + value[0];
					}

				}else if(vcData[i] != '\r'){
					lineLength++;
				}
			}catch (Exception e) {
				log.warn("vCardファイル("+id+"件目)に問題があります");
			}
		}
	}
	/*
	 * XXX
	 * 既存のAPIを使用するためImodeMailへ変換
	 */
	ImodeMail message2imodemail(Message msg){
		ImodeMail imodemail = new ImodeMail();
		try{
			imodemail.setFromAddr((InternetAddress)msg.getFrom()[0]);
			imodemail.setSubject(msg.getSubject());
			imodemail.setDecomeFlg(false);
			SimpleDateFormat df = new SimpleDateFormat("yyyyMMddHHmmss");
			imodemail.setTime(df.format(msg.getSentDate()));
			imodemail.setBody("(表示省略)");
		}catch(MessagingException e){
			log.warn("例外処理省略",e);
		}
		return imodemail; 
	}
}
