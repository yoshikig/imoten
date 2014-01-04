/*
 * imoten - i mode.net mail tensou(forward)
 *
 * Copyright (C) 2013, 2014 ryu aka 508.P905 (http://code.google.com/p/imoten/)
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

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;

import java.util.Date;
import java.util.LinkedList;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SpmodePop3Reader extends SpmodeReader{
	private static final Log log = LogFactory.getLog(ServerMain.class);
	private static final String InitialId = "0";

	private Config conf;
	private StatusManager status;

	private Properties props;
	private String myname = "";
	private String passwd = "";
	private int unknownForwardLimit;

	private Date initialCheckDate = null;
	private Folder folder;
	private LinkedList<Message> allMessages = new LinkedList<Message>();
	private Message latestMessage = null;
	private String lastPollId = "";
	
	private SpmodeImapReader imapreader = null;
	private boolean syncImapFolder = false;
	private boolean syncImapOnly = false;

	public SpmodePop3Reader(ServerMain server){
		this.conf = server.conf;
		this.status = server.status;

		this.props = new Properties();
		log.info("spmode: pop3");
		props.setProperty("mail.pop3.host", "mail.spmode.ne.jp");
		props.setProperty("mail.pop3.port", "995");
		props.setProperty("mail.pop3.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		props.setProperty("mail.pop3.socketFactory.fallback", "false");
		props.setProperty("mail.pop3.socketFactory.port", "995");

		// XXX 設定可能にする？
		//props.setProperty("mail.pop3.connectiontimeout", XXX);
		//props.setProperty("mail.pop3.timeout", XXX);

		this.myname = conf.getDocomoId();
		this.passwd = conf.getDocomoPasswd();

		this.unknownForwardLimit = this.conf.getSpmodeUnknownForwardLimit();
		this.syncImapFolder = this.conf.isSpmodePop3SyncImap();
		this.syncImapOnly = this.conf.isSpmodePop3SyncOnly();
	}
	
	public Store connect(Store str, boolean readSent) throws MessagingException {
		
		Session session = null;
		Store store = null;
		if(str==null || !str.isConnected()){
			session = Session.getInstance(props, null);
			session.setDebug(conf.isMailDebugEnable());
		
			store = session.getStore("pop3");
			store.connect(myname, passwd);
		} else {
			store = str;
		}
		
		latestMessage = null;
		
		Folder rootFolder = store.getDefaultFolder();
		folder = rootFolder.getFolder("INBOX");
		if(conf.isSpmodeReadonly()){
			folder.open(Folder.READ_ONLY);
		}else{
			try{
				folder.open(Folder.READ_WRITE);
			}catch(MessagingException me){
				folder.open(Folder.READ_ONLY);
			}
		}
		return store;
	}

	public void getMessages() throws MessagingException {
		if(allMessages.size()>0){
			log.info("未転送のメールが残っているため新規メール取得はスキップします。");
			return;
		}else{
			this.lastPollId = "";
		}
		
		String lastId = getLastId();
		Message messages[] = folder.getMessages();
		int messageLength = folder.getMessageCount();
		
		//IDが設定されてた時、当該IDのメールを降順(新しいメールから順)に探す
		int start = -1;
		Message msg;
		for (int index = messageLength-1; index >= 0; index--) {
			msg = messages[index];
			if(index == messageLength-1){
				latestMessage = msg;
				if(StringUtils.isBlank(lastId)){
					log.info("lastspmsgIdが空なのでメール取得をスキップし、次回受信したメールから転送を開始します。");
					return;
				}
			}
			String thisId = getId(msg);
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
					this.lastPollId = InitialId;
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
		}

		//メールの取得
		for (int index = start + 1; index < messageLength; index++) {
			msg = messages[index];

			StringBuilder maildata = Util.dumpMessage(msg);
			log.info("取得メール情報\n"+maildata);

			allMessages.add(msg);
			if(syncImapFolder){
				try{
					imapreader.putPop3Mail(msg);
				} catch (Exception e){
					log.error("IMAP同期処理でエラー発生",e);
				}
			}
		}
		
		if(syncImapFolder && getMessageCount()>0){
			try{
				imapreader.savePop3Mail();
			} catch (Exception e){
				log.error("IMAP同期処理でエラー発生",e);
			}
			if(syncImapOnly){
				allMessages.clear();
			}
		}
	}
	
	public int getMessageCount() {
		return allMessages.size();
	}
	
	public Message readMessage() {
		Message m = allMessages.pollFirst(); 
		this.lastPollId = getId(m);
		return m;
	}

	public void restoreMessage(String id, Message msg) {
		allMessages.addFirst(msg);
	}

	public String getLatestId() {
		if (!lastPollId.isEmpty()){
			return lastPollId;
		} else if (latestMessage!=null){
			return getId(latestMessage);
		}else{
			return "";
		}
	}
	
	public void updateLastId() {
		this.status.setLastSpMsgId(getLatestId());
		log.info("lastspmsgIdを更新しました");
		try{
			this.status.save();
			log.info("statusファイルを保存しました");
		}catch (Exception e) {
			log.error("Status File save Error.",e);
		}
	}
	
	protected String getLastId() {
		return this.status.getLastSpMsgId();
	}
	
	public void waitMessage() {
		try{
			Thread.sleep(conf.getSpmodeCheckIntervalSec() * 1000);
		}catch (Exception e) {}
	}
	
	public void close() {
		closeFolder();
	}
	private void closeFolder() {
		try{
			if(folder.isOpen()){
				folder.close(false);
			}
		}catch (MessagingException e){}
	}
	
	private String getId(Message m) {
		try{
			return m.getHeader("Message-ID")[0];
		}catch (Exception e){
			return "";
		}
	}
	
	public void setImapReader(SpmodeImapReader imapreader){
		this.imapreader = imapreader;
	}
}