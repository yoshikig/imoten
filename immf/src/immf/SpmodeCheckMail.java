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

import javax.mail.AuthenticationFailedException;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.IllegalStateException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.mail.EmailException;

public class SpmodeCheckMail implements Runnable {
	private static final Log log = LogFactory.getLog(ServerMain.class);

	private Config conf;
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
	private boolean forwardWithoutPush = false;
	private boolean forwardSent = false;
	private SpmodeReader sr;
	private boolean hasImapSentFolder = false;

	public SpmodeCheckMail(ServerMain server, String protocol){
		this.conf = server.conf;
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
		if(protocol.equalsIgnoreCase("imap")){
			this.sr = new SpmodeImapReader(server);
			this.hasImapSentFolder = true;
		}else{
			this.sr = new SpmodePop3Reader(server);
		}
		for (Map.Entry<Config, ForwardMailPicker> f : forwarders.entrySet()) {
			Config forwardConf = f.getKey();
			if(forwardConf.getForwardOnly()==Config.ForwardOnly.PUSH){
				this.forwardWithoutPush = true;
			}
			if(forwardConf.isForwardSent()){
				this.forwardSent = true;
			}
		}
	}

	public void run() {
		
		// 読み込みは初回起動時のみ
		this.loadAddressBook();

		boolean rcvFailFlg = false;
		boolean fwdFailFlg = false;
		int intervalSec = 0;
		int fwdRetryLimit = conf.getForwardRetryMaxCount();
		int fwdRetryCount = 0;
		Store store = null;
		
		while(true){
			boolean forwarded = false;
			if (rcvFailFlg){
				intervalSec = Math.min(3600, intervalSec*2);
			}else if(fwdFailFlg){
				intervalSec = conf.getForwardRetryIntervalSec();
			}else{
				intervalSec = conf.getSpmodeCheckIntervalSec();
			}

			try{
				store = sr.connect(store, this.forwardSent);
				
				//メールの取得
				sr.getMessages();
				int count = sr.getMessageCount();
				rcvFailFlg = false;

				//メールの転送
				log.info("転送するメールIDの数 "+ count);
				appNotifications.pushPrepare(0, count);
				while (sr.getMessageCount()>0) {
					Message msg = sr.readMessage();
					String id = sr.getLatestId();
					log.info("メール転送:"+id);
					try{
						this.forward(msg, id);
					}catch(Exception e){
						if(fwdRetryLimit>0 && fwdRetryCount>=fwdRetryLimit){
							fwdRetryCount = 0;
							log.warn("リトライ上限到達。メッセージID:"+id+"の転送を中止しました");
						}else{
							//転送予定だったメールを書き戻す
							sr.restoreMessage(id, msg);
						}
						throw e;
					}
					fwdFailFlg = false;
					fwdRetryCount = 0;
					
					// 転送処理中に届いた新着メールがあった場合に待ちに入る前に拾うためのフラグ
					forwarded = true;
				}
				
				// status.ini の更新
				sr.updateLastId();

			} catch(AuthenticationFailedException afe){
				if (afe.getMessage().equalsIgnoreCase("Unknown User")){
					log.error("spモードメール(pop3)のユーザ名またはパスワードが間違っています。受信メールのチェックを終了します。",afe);
					return;
				} else if (afe.getMessage().equalsIgnoreCase("authenticate failed")){
					log.error("ドコモメールの利用開始設定がされていないか、docomoIDかパスワードの設定が間違っています。受信メールのチェックを終了します。",afe);
					return;
				} else {
					// Transaction failed
					log.warn("spモードメールサーバメンテナンス中により認証失敗。間隔を置いてリトライします。",afe);
					if(!rcvFailFlg){
						intervalSec = 600;
						rcvFailFlg = true;
					}
				}
			} catch(MessagingException me){
				log.warn("ドコモメールサーバ接続時に例外発生",me);
				if(!rcvFailFlg){
					intervalSec = 60;
					rcvFailFlg = true;
				}
			} catch(IllegalStateException ie){
				log.warn("フォルダクローズのためリトライします",ie);
				if(!rcvFailFlg){
					intervalSec = 10;
					rcvFailFlg = true;
				}
			} catch(EmailException ee){
				if(fwdFailFlg && fwdRetryCount==0){
					log.error("spモードメール転送エラー、リトライで回復せずメール転送中止。",ee);
					fwdFailFlg = false;
				}else{
					if(!fwdFailFlg){
						// 初回
						fwdFailFlg = true;
					}
					log.warn("spモードメール転送エラー、後でメール転送リトライします。",ee);
					fwdRetryCount++;
				}
			} catch(Exception e) {
				log.error("例外発生",e);
				if(!rcvFailFlg){
					// これを拾うのはimotenのバグが原因の可能性が高いが連続アクセスを抑止するため強制1時間スリープ
					intervalSec = 3600;
					rcvFailFlg = true;
				}
			}

			// 転送処理中の受信メールを拾うため一回メールチェックをする
			if (!rcvFailFlg && !fwdFailFlg) {
				if(forwarded){
					try{
						Thread.sleep(1000);
					}catch (Exception e) {}
					continue;
				}
			}

			// 次のチェックまで待つ
			if (!rcvFailFlg && !fwdFailFlg) {
				sr.waitMessage();
			}else{
				try{
					Thread.sleep(intervalSec*1000);
				}catch (Exception e) {}
			}
			
			// クローズ処理
			sr.close();
		}
	}
	
	public Folder getSentFolder() {
		if(hasImapSentFolder){
			return ((SpmodeImapReader)sr).getSentFolder();
		}else{
			return null;
		}
	}
	
	/*
	 * メールをダウンロードして送信
	 */
	private void forward(Message msg, String mailId) throws Exception{
		String from;
		try {
			from = ((InternetAddress)msg.getFrom()[0]).getAddress();
		} catch (MessagingException e) {
			from = "";
		}
		List<String> ignoreDomains = new ArrayList<String>();
		ImodeMail mail = null;
		try{
			// 送信
			for (Map.Entry<Config, ForwardMailPicker> f : forwarders.entrySet()) {
				Config forwardConf = f.getKey();
				int id = forwardConf.getConfigId();

				if(hasImapSentFolder && forwardSent){
					if(!forwardConf.isForwardSent() && msg.getHeader(SpmodeImapReader.sentHeader)!=null){
						// 送信メールは転送しない
						continue;
					}
				}
				
				if(forwardConf.getForwardOnly()==Config.ForwardOnly.Imode){
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
				if(this.forwardWithoutPush){
					// imoten.iniにforward.only=pushが一つでもある場合
					if(forwardConf.getForwardOnly()!=Config.ForwardOnly.PUSH){
						forwardMail.send();
					}else{
						mail = forwardMail.getImodeMail();
					}
				}else{
					mail = (mail!=null) ? mail : forwardMail.getImodeMail();
					forwardMail.send();
				}
				if(numForwardSite>1){
					log.info("転送処理完了["+id+"]");
				}else{
					log.info("転送処理完了");
				}
			}

		}catch (Exception e) {
			log.error("mail["+mailId+"] forward Error.");
			throw e;
		}

		if(mail==null){
			this.appNotifications.pushError(0);
			try{
				// 負荷をかけないように
				Thread.sleep(1000);
			}catch (Exception e) {}
			return;
		}

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
}
