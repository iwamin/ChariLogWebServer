package com.charilog.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.charilog.api.param.GPSElement;
import com.charilog.api.param.ReqAccountInfo;
import com.charilog.api.param.ReqDeleteCyclingRecord;
import com.charilog.api.param.ReqDownloadGPSData;
import com.charilog.api.param.ReqInvalidateKey;
import com.charilog.api.param.ReqUploadCyclingRecord;
import com.charilog.api.param.ReqUploadGPSData;
import com.charilog.api.param.ResDownloadCyclingRecord;
import com.charilog.api.param.ResDownloadGPSData;
import com.charilog.api.param.ResUploadCyclingRecord;
import com.charilog.domain.CyclingRecord;
import com.charilog.domain.GPSData;
import com.charilog.domain.KeyToRecord;
import com.charilog.domain.User;
import com.charilog.lib.CommonLib;
import com.charilog.service.CyclingRecordService;
import com.charilog.service.GPSDataService;
import com.charilog.service.KeyToRecordService;
import com.charilog.service.UserService;

@RestController
@RequestMapping("")
public class ChariLogRestController {
	@Autowired
	private UserService userService;

	@Autowired
	private CyclingRecordService cyclingRecordService;

	@Autowired
	private GPSDataService gpsDataService;
	
	@Autowired
	private KeyToRecordService keyToRecordService;

	// ユーザー作成
	@RequestMapping(value = "account/create", method = RequestMethod.POST)
	public ResponseEntity<User> createUser(@RequestBody ReqAccountInfo requestBody) {
		User user = new User(requestBody.getUserId(), requestBody.getPassword());
		ResponseEntity<User> response;

		if (userService.isExisting(user)) {
			response = new ResponseEntity<>(user, null, HttpStatus.CONFLICT);
		} else {
			User created = userService.create(user);
			response = new ResponseEntity<>(created, null, HttpStatus.CREATED);
		}
		return response;
	}

	// ユーザー削除
	@RequestMapping(value = "account/delete", method = RequestMethod.POST)
	public ResponseEntity<Object> deleteUser(@RequestBody ReqAccountInfo requestBody) {
		// ユーザー情報を認証する
		User requestUser = new User(requestBody.getUserId(), requestBody.getPassword());
		if (!userService.authenticate(requestUser)) {
			// 認証失敗の場合は、UNAUTHORIZEDを応答し、終了する。
			return new ResponseEntity<>(null, null, HttpStatus.UNAUTHORIZED);
		}

		List<CyclingRecord> records = cyclingRecordService.findByUserId(requestUser.getUserId());
		for (CyclingRecord record : records) {
			// 走行記録テーブルから削除
			cyclingRecordService.delete(record.getRecordId());
			// GPSテーブルから削除
			gpsDataService.deleteByRecordId(record.getRecordId());
		}
		userService.delete(requestUser);

		return new ResponseEntity<>(null, null, HttpStatus.NO_CONTENT);
	}

	// 走行記録1件登録
	@RequestMapping(value = "record/upload", method = RequestMethod.POST)
	public ResponseEntity<ResUploadCyclingRecord> uploadRecord(@RequestBody ReqUploadCyclingRecord requestBody) {
		// ユーザー情報を認証する
		User requestUser = new User(requestBody.getUserId(), requestBody.getPassword());
		if (!userService.authenticate(requestUser)) {
			// 認証失敗の場合は、UNAUTHORIZEDを応答し、終了する。
			return new ResponseEntity<ResUploadCyclingRecord>(null, null, HttpStatus.UNAUTHORIZED);
		}

		// 前回送信時に処理が中断された場合、同じデータが登録されている可能性があるので、登録前に削除する()。
		CyclingRecord old = cyclingRecordService.find(
				requestBody.getUserId(), requestBody.getDeviceId(), requestBody.getDateTime());
		if (old != null) {
			// 走行記録テーブルから削除
			cyclingRecordService.delete(old.getRecordId());
			// GPSテーブルから削除
			gpsDataService.deleteByRecordId(old.getRecordId());
		}

		// 走行記録テーブルに登録する
		CyclingRecord record = cyclingRecordService.create(requestBody);
		if (record == null) {
			// 登録できなかった場合は、NOT_ACCEPTABLEを応答し、終了する。
			return new ResponseEntity<ResUploadCyclingRecord>(null, null, HttpStatus.NOT_ACCEPTABLE);
		}

		// GPSデータ登録用Keyを作成する
		String key = CommonLib.encryptSHA256(record.getUserId() + record.getRecordId());

		// 作成したkeyをkey管理用テーブルに登録しておく
		KeyToRecord entity = new KeyToRecord(key, record.getRecordId(), record.getUserId());
		KeyToRecord registered = keyToRecordService.register(entity);
		if (registered == null) {
			// 登録できなかった場合は、NOT_ACCEPTABLEを応答し、終了する。
			return new ResponseEntity<ResUploadCyclingRecord>(null, null, HttpStatus.NOT_ACCEPTABLE);
		}

		// BodyにGPSデータ登録用Keyを格納して、ACCEPTEDを応答する。
		ResUploadCyclingRecord jsonBody = new ResUploadCyclingRecord(key);
		return new ResponseEntity<ResUploadCyclingRecord>(jsonBody, null, HttpStatus.ACCEPTED);
	}

	// 指定されたユーザーの走行記録を取得
	@RequestMapping(value = "record/download", method = RequestMethod.POST)
	public ResponseEntity<List<ResDownloadCyclingRecord>> downloadRecord(@RequestBody ReqAccountInfo requestBody) {
		// ユーザー情報を認証する
		User requestUser = new User(requestBody.getUserId(), requestBody.getPassword());
		if (!userService.authenticate(requestUser)) {
			// 認証失敗の場合は、UNAUTHORIZEDを応答し、終了する。
			return new ResponseEntity<List<ResDownloadCyclingRecord>>(null, null, HttpStatus.UNAUTHORIZED);
		}

		// 指定されたユーザーの走行記録を取得する
		List<CyclingRecord> records = cyclingRecordService.findByUserId(requestUser.getUserId());
		List<ResDownloadCyclingRecord> response = new ArrayList<>();
		for (CyclingRecord e : records) {
			response.add(new ResDownloadCyclingRecord(e));
		}
		return new ResponseEntity<List<ResDownloadCyclingRecord>>(response, null, HttpStatus.OK);
	}

	// 走行記録1件を削除
	@RequestMapping(value = "record/delete", method = RequestMethod.POST)
	public ResponseEntity<Object> deleteRecord(@RequestBody ReqDeleteCyclingRecord requestBody) {
		// ユーザー情報を認証する
		User requestUser = new User(requestBody.getUserId(), requestBody.getPassword());
		if (!userService.authenticate(requestUser)) {
			// 認証失敗の場合は、UNAUTHORIZEDを応答し、終了する。
			return new ResponseEntity<>(null, null, HttpStatus.UNAUTHORIZED);
		}

		// 指定されたレコードIDがそのユーザーのものかを確認する
		CyclingRecord record = cyclingRecordService.findOne(requestBody.getRecordId());
		if ((record != null)
				&& (record.getUserId().equals(requestBody.getUserId()))) {
			// 走行記録テーブルから削除
			cyclingRecordService.delete(requestBody.getRecordId());
			// GPSテーブルから削除
			gpsDataService.deleteByRecordId(requestBody.getRecordId());
			return new ResponseEntity<>(null, null, HttpStatus.NO_CONTENT);
		} else {
			return new ResponseEntity<>(null, null, HttpStatus.FORBIDDEN);
		}
	}
	
	// GPSデータ(走行記録1件分)を登録
	@RequestMapping(value = "gps/upload", method = RequestMethod.POST)
	public ResponseEntity<Object> uploadGPSData(@RequestBody ReqUploadGPSData requestBody) {
		System.out.println(requestBody.toString());

		// key管理用テーブルからそのkeyに対応するrecordIdを取得する
		KeyToRecord result = keyToRecordService.find(requestBody.getKey());
		if (result == null) {
			// 不明なkeyの場合、認証失敗として終了する
			return new ResponseEntity<>(null, null, HttpStatus.UNAUTHORIZED);
		}

		// GPSデータを記録する
		Integer recordId = result.getRecordId();
		for (GPSElement element : requestBody.getData()) {
			GPSData created = gpsDataService.create(element, recordId);
			if (created == null) {
				return new ResponseEntity<>(null, null, HttpStatus.NOT_ACCEPTABLE);
			}
		}
		return new ResponseEntity<>(null, null, HttpStatus.ACCEPTED);
	}

	// GPSデータ(走行記録1件分)を取得
	@RequestMapping(value = "gps/download", method = RequestMethod.POST)
	public ResponseEntity<ResDownloadGPSData> downloadGPSData(@RequestBody ReqDownloadGPSData requestBody) {
		// ユーザー情報を認証する
		User requestUser = new User(requestBody.getUserId(), requestBody.getPassword());
		if (!userService.authenticate(requestUser)) {
			// 認証失敗の場合は、UNAUTHORIZEDを応答し、終了する。
			return new ResponseEntity<>(null, null, HttpStatus.UNAUTHORIZED);
		}

		// 指定されたレコードIDがそのユーザーのものかを確認する
		CyclingRecord record = cyclingRecordService.findOne(requestBody.getRecordId());
		if ((record != null)
				&& (record.getUserId().equals(requestBody.getUserId()))) {
			ResDownloadGPSData response = new ResDownloadGPSData();
			List<GPSData> gpsList = gpsDataService.findByRecordId(record.getRecordId());
			List<GPSElement> gpsElements = new ArrayList<>();
			for (GPSData e : gpsList) {
				gpsElements.add(new GPSElement(e));
			}
			response.setRecordId(record.getRecordId());
			response.setData(gpsElements.toArray(new GPSElement[0]));
			return new ResponseEntity<>(response, null, HttpStatus.OK);
		} else {
			return new ResponseEntity<>(null, null, HttpStatus.FORBIDDEN);
		}

		
	}

	// GPSデータ登録用keyの無効化要求
	@RequestMapping(value = "gps/invalidate-key", method = RequestMethod.POST)
	public ResponseEntity<Object> invalidateKey(@RequestBody ReqInvalidateKey body) {
		keyToRecordService.delete(body.getKey());
		return new ResponseEntity<>(null, null, HttpStatus.ACCEPTED);
	}

	// ユーザーリスト取得(※デバッグ用)
//	@RequestMapping(value = "account", method = RequestMethod.GET)
//	public List<User> getUserList() {
//		return userService.findAll();
//	}

	// 走行記録リストを取得(※デバッグ用)
//	@RequestMapping(value = "record2", method = RequestMethod.GET)
//	public List<CyclingRecord> findAll() {
//		return cyclingRecordService.findAll();
//	}

	// GPSデータテーブルの取得(※デバッグ用)
//	@RequestMapping(value = "gps/download", method = RequestMethod.GET)
//	public List<GPSData> downloadGPSData() {
//		return gpsDataService.findAll();
//	}

	// JSON内容表示(※デバッグ用)
	@RequestMapping(value = "test", method = RequestMethod.POST)
	public void testPost(@RequestBody String requestBody, @RequestHeader("Content-Type") String type) {
		System.out.println("TEST:\n" + type + "\n" + requestBody);
	}
}
