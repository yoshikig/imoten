package immf;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.mail.internet.InternetAddress;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import immf.google.contact.GoogleContactsAccessor;

public class AddressBook {
	private static final Log log = LogFactory.getLog(AddressBook.class);

	// メールアドレスからimodeAddressを検索
	private Map<String, ImodeAddress> pcAddrMap;		// iモード.net 上で登録したアドレス帳
	private Map<String, ImodeAddress> dsAddrMap;		// ケータイデータお預かりサービスの携帯電話帳
	private Map<String, ImodeAddress> csvAddrMap;		// CSVの電話帳
	private Map<String, ImodeAddress> vcAddrMap;		// vCardの電話帳

	private Date created;

	public AddressBook(){
		this.created = new Date();
		this.pcAddrMap = new HashMap<String, ImodeAddress>();
		this.dsAddrMap = new HashMap<String, ImodeAddress>();
		this.csvAddrMap = new HashMap<String, ImodeAddress>();
		this.vcAddrMap = new HashMap<String, ImodeAddress>();
	}

	/*
	 * メールアドレスから名前の入ったImodeAddressを取得
	 * 以下の順で優先される
	 *
	 * 1. Google Contacts APIから取得できた情報
	 * 2. vCardファイル
	 * 3. CSVファイル
	 * 4. iモード.netの簡易アドレス帳
	 * 5. ケータイデータお預かりサービスの携帯電話帳
	 */
	public ImodeAddress getImodeAddress(String mailAddress){
		ImodeAddress r = null;
		if(GoogleContactsAccessor.isInitialized())
		{
			r = GoogleContactsAccessor.getInstance().getGoogleContact(mailAddress);
		}
		if(r!=null){
			return r;
		}
		r = this.vcAddrMap.get(mailAddress);
		if(r!=null){
			return r;
		}
		r = this.csvAddrMap.get(mailAddress);
		if(r!=null){
			return r;
		}
		r = this.pcAddrMap.get(mailAddress);
		if(r!=null){
			return r;
		}
		return this.dsAddrMap.get(mailAddress);

	}

	public InternetAddress getInternetAddress(String mailAddress, String charset){
		ImodeAddress ia = this.getImodeAddress(mailAddress);
		try{
			if(ia==null){
				return new InternetAddress(mailAddress);
			}else{
				return new MyInternetAddress(mailAddress,ia.getName(), charset);
			}
		}catch (Exception e) {
			log.warn("mail addrress format error.["+mailAddress+"]",e);
			try{
				return new InternetAddress(mailAddress);
			}catch (Exception ex) {
				try{
					// メールアドレスなしのiモードセンターからのリターンメール
					return new InternetAddress("", mailAddress);
				}catch (Exception exc) {
					return null;
				}
			}
		}
	}

	public InternetAddress getInternetAddress(InternetAddress iAddress, String charset){
		String mailAddress = iAddress.getAddress();
		ImodeAddress ia = this.getImodeAddress(mailAddress);
		try{
			if(ia==null){
				return new MyInternetAddress(mailAddress,iAddress.getPersonal(), charset);
			}else{
				return new MyInternetAddress(mailAddress,ia.getName(), charset);
			}
		}catch (Exception e) {
			log.warn("mail addrress format error.["+mailAddress+"]",e);
			return iAddress;
		}
	}

	public void addPcAddr(ImodeAddress ia){
		this.pcAddrMap.put(ia.getMailAddress(), ia);
	}

	public void addDsAddr(ImodeAddress ia){
		this.dsAddrMap.put(ia.getMailAddress(), ia);
	}

	public void addCsvAddr(ImodeAddress ia){
		this.csvAddrMap.put(ia.getMailAddress(), ia);
	}

	public void addVcAddr(ImodeAddress ia){
		this.vcAddrMap.put(ia.getMailAddress(), ia);
	}

	public Date getCreated(){
		return created;
	}
}
