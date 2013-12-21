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
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.FolderClosedException;
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
import java.lang.IllegalStateException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.mail.EmailException;

import com.sun.mail.imap.IMAPFolder;

public class SpmodeCheckMail implements Runnable{
	private static final Log log = LogFactory.getLog(ServerMain.class);
	private static final String InitialId = "0";

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
	private int unknownForwardLimit;
	private boolean forwardWithoutPush = false;

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
		this.unknownForwardLimit = this.conf.getSpmodeUnknownForwardLimit();
		for (Map.Entry<Config, ForwardMailPicker> f : forwarders.entrySet()) {
			Config forwardConf = f.getKey();
			if(forwardConf.getForwardOnly()==Config.ForwardOnly.PUSH){
				this.forwardWithoutPush = true;
			}
		}
	}

	public void run() {
		String myname = "";
		String passwd = "";

		String protocol = conf.getSpmodeProtocol();
		Properties props = new Properties();

		if(protocol.equalsIgnoreCase("pop3")){
			log.info("spmode: pop3");
			props.setProperty("mail.pop3.host", "mail.spmode.ne.jp");
			props.setProperty("mail.pop3.port", "995");
			props.setProperty("mail.pop3.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
			props.setProperty("mail.pop3.socketFactory.fallback", "false");
			props.setProperty("mail.pop3.socketFactory.port", "995");

			// XXX 設定可能にする？
			//props.setProperty("mail.pop3.connectiontimeout", XXX);
			//props.setProperty("mail.pop3.timeout", XXX);

			myname = conf.getSpmodeMailUser();
			passwd = conf.getSpmodeMailPasswd();
		}else{
			log.info("spmode: imap");
			props.setProperty("mail.imap.host", "imap2.spmode.ne.jp");
			props.setProperty("mail.imap.port", "993");
			props.setProperty("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
			props.setProperty("mail.imap.socketFactory.fallback", "false");
			props.setProperty("mail.imap.socketFactory.port", "993");

			//props.setProperty("mail.imap.connectiontimeout", XXX);
			//props.setProperty("mail.imap.timeout", XXX);

			myname = conf.getDocomoId();
			passwd = conf.getDocomoPasswd();
		}

		// 読み込みは初回起動時のみ
		this.loadAddressBook();

		//Date lastUpdate = null;
		Date initialCheckDate = null;
		boolean rcvFailFlg = false;
		boolean fwdFailFlg = false;
		int intervalSec = 0;
		int fwdRetryLimit = conf.getForwardRetryMaxCount();
		int fwdRetryCount = 0;
		Folder folder = null;
		Session session = null;
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
				if(store==null || !store.isConnected()){
					session = Session.getInstance(props, null);
					session.setDebug(conf.isMailDebugEnable());
				
					store = session.getStore(protocol);
					store.connect(myname, passwd);
				}
				
				folder = store.getDefaultFolder();
				folder = folder.getFolder("INBOX");
				if(conf.isSpmodeReadonly() && !(folder instanceof IMAPFolder)){
					folder.open(Folder.READ_ONLY);
				}else{
					try{
						folder.open(Folder.READ_WRITE);
					}catch(MessagingException me){
						folder.open(Folder.READ_ONLY);
					}
				}

				Message messages[] = folder.getMessages();
				int messageLength = folder.getMessageCount();
				if(StringUtils.isBlank(this.status.getLastSpMsgId())){
					//IDが未設定の時
					Message msg = messages[messageLength-1];
					thisId = msg.getHeader("Message-ID")[0];
				}else{
					String lastId = this.status.getLastSpMsgId();
					//IDが設定されてた時、当該IDのメールを降順(新しいメールから順)に探す
					int start = -1;
					Message msg;
					for (int index = messageLength-1; index >= 0; index--) {
						msg = messages[index];
						thisId = msg.getHeader("Message-ID")[0];
						log.info("ID探索:"+index+","+thisId);
						if (thisId.equals(lastId)) {
							start = index;
							break;
						}
					}
					
					/*
					 * メールボックスが空なので次回からすべてのメールを再転送すればよいが、
					 * 空になってから15分間はドコモメールサーバの異常を疑う
					 * 15分間は thisId が設定されないため status.ini の更新も行われない
					 */
					if(messageLength == 0 && !lastId.equals(InitialId)){
						if(initialCheckDate != null){
							long diff = System.currentTimeMillis() - initialCheckDate.getTime();
							if(diff > 15*60*1000){
								initialCheckDate = null;
								thisId = InitialId;
							}
						} else {
							initialCheckDate = new Date();
						}
					}
					
					//該当するIDのメールが削除されていた場合、全再送を防ぐため上限数を設定する。上限数マイナスの場合は上限設定無効
					int recievemax = this.unknownForwardLimit;
					if (messageLength > 0 && start < 0 && recievemax >= 0 && !lastId.equals(InitialId)){
						log.warn("最後に取得したメールが発見できなかったため最新の" + recievemax + "通(spmode.unknownforwardlimit)を上限としてメール転送します");
						start = messageLength - Math.min(messageLength,recievemax) - 1;
						
						//IDを最新メールに再設定する
						thisId = messages[messageLength-1].getHeader("Message-ID")[0];
					}
					
					//メールの取得と転送
					log.info("転送するメールIDの数 "+(messageLength - start - 1));
					appNotifications.pushPrepare(0, messageLength - start - 1);
					for (int index = start + 1; index < messageLength; index++) {
						msg = messages[index];
						boolean seen = msg.isSet(Flags.Flag.SEEN);
						String id = "";
						try {
							byte contentData[] = Util.inputstream2bytes(msg.getInputStream());
							log.debug("Content-Type:"+msg.getContentType());
							log.debug("Content[\n"+new String(contentData)+"\n]");
						}catch(Exception e){}

						id = msg.getHeader("Message-ID")[0]; 
						log.info("メール転送:"+index+","+id);
							
						// リトライ上限に達している場合はforwardの成否に関わらずidを設定して次メールへ進む
						if(fwdRetryLimit>0 && fwdRetryCount>=fwdRetryLimit){
							thisId = id;
							fwdRetryCount = 0;
						}
						try{
							this.forward(msg, id);
						}catch(Exception e){
							throw e;
						}
						thisId = id;
						rcvFailFlg = false;
						fwdFailFlg = false;
						fwdRetryCount = 0;
						forwarded = true;
						
						// 未読フラグのセットでエラーが発生しても転送リトライしない
						if(folder instanceof IMAPFolder && folder.getMode()==Folder.READ_WRITE){
							try{
								msg.setFlag(Flags.Flag.SEEN, seen);
								if(!seen){
									log.info("メッセージ"+id+"を未読状態に戻しました");
								}
							}catch(MessagingException e){}
						}
					}
				}
				
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
			if (!rcvFailFlg && !fwdFailFlg && folder.isOpen() && folder instanceof IMAPFolder) {
				try{
					if(forwarded){
						continue;
					}
					IMAPFolder f = (IMAPFolder)folder;
					Thread ImapIdleTimer = new Thread(new ImapIdleTimer());
					ImapIdleTimer.setUncaughtExceptionHandler(new ImapFolderCloser(f));
					ImapIdleTimer.start();
					log.info("IMAP IDLE開始");
					f.idle(true);
					log.info("IMAP IDLE完了");
				}catch(FolderClosedException fce){
					log.info("IMAP IDLE中断(サーバとの接続が切断されました)");
				}catch(Exception e){
					log.warn("例外発生",e);
					try{
						Thread.sleep(intervalSec*1000);
					}catch (Exception e2) {}
				}
			}else{
				try{
					if(folder.isOpen()){
						folder.close(false);
					}
				}catch (MessagingException e){}
				try{
					Thread.sleep(intervalSec*1000);
				}catch (Exception e) {}
			}
		}

	}
	class ImapIdleTimer implements Runnable {
		public void run() {
			try{
				// 10分弱待つ
				Thread.sleep(590*1000);
			}catch(Exception e){}
			throw new RuntimeException();
		}
	}
	class ImapFolderCloser implements UncaughtExceptionHandler {
		private IMAPFolder folder;
		public ImapFolderCloser(IMAPFolder f) {
			folder = f;
		}
		public void uncaughtException(Thread thread, Throwable throwable) {
			try{
				folder.close(false);
			}catch(Exception e){}
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
