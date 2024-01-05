package net.aotake91.memowithlogstamp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Path.of
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val timer = object : Runnable {
        // 日時更新タスク（1秒間隔）
        override fun run() {
            getAndViewDateTimeText()
            handler.postDelayed(this, 1000)
        }
    }

    // 現在日時
    private var nowDate = ""
    private var nowTime = ""

    // 位置情報
    private var _latitude = 0.0
    private var _longitude = 0.0
    private lateinit var _fusedLocationClient : FusedLocationProviderClient
    private lateinit var _locationRequest : LocationRequest
    private lateinit var _onUpdateLocation : OnUpdateLocation

    // 保存ファイル名
    private var _saveFileName = ""
    // ストレージアクセス許可の有無
    private var allowed_access_to_storage = false

    // 位置情報更新時のコールバック
    private inner class OnUpdateLocation : LocationCallback() {
        override fun onLocationResult(p0: LocationResult) {
            val location = p0.lastLocation
            location?.let {
                // 緯度・経度を取得
                _latitude = it.latitude
                _longitude = it.longitude
                // 緯度・経度をTextViewに表示
                val lat = findViewById<TextView>(R.id.nowLatitude)
                val lng = findViewById<TextView>(R.id.nowLongitude)
                lat.text = String.format("%.5f", _latitude)
                lng.text = String.format("%.5f", _longitude)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 日時更新を定期タスクとして登録
        handler.post(timer)

        // 位置情報関係オブジェクトの登録
        _fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@MainActivity)
        val builder = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000)
        _locationRequest = builder.build()
        _onUpdateLocation = OnUpdateLocation()

        // 各ボタンにイベントリスナを登録
        val btnInsertTimestamp = findViewById<Button>(R.id.btnInsertTimestamp)
        val btnInsertPos = findViewById<Button>(R.id.btnInsertPos)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnMapView = findViewById<Button>(R.id.btnViewCurrentPosOnMap)
        val buttonListener = ButtonListener()

        btnInsertTimestamp.setOnClickListener(buttonListener)
        btnInsertPos.setOnClickListener(buttonListener)
        btnSave.setOnClickListener(buttonListener)
        btnMapView.setOnClickListener(buttonListener)

        // チェックボックスにイベントリスナを登録
        val chkGPSEnable = findViewById<CheckBox>(R.id.gpsEnable)
        val checkBoxListener = CheckBoxListener()
        chkGPSEnable.setOnClickListener(checkBoxListener)

        // ストレージアクセス許可の確認（権限リクエスト）
        requestPermissionOfStorageReadWrite()

        // メモファイルのロード（存在する場合）
        loadMemoFromFile()

    }

    override fun onResume() {
        super.onResume()
        val gpsEnableCheckBox = findViewById<CheckBox>(R.id.gpsEnable)
        // 位置情報計測が有効化されている場合
        if (gpsEnableCheckBox.isChecked) {
            // 位置情報利用の再開（許可されていない場合は再度許可を求める）
            requestPermissionOfLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        // 位置情報の計測を停止
        _fusedLocationClient.removeLocationUpdates(_onUpdateLocation)
    }

    // 各ボタンのイベントリスナ
    private inner class ButtonListener : View.OnClickListener {
        override fun onClick(v: View) {
            when (v.id) {
                R.id.btnViewCurrentPosOnMap -> {
                    onViewCurrentPosOnMapButtonClick()
                }
                R.id.btnInsertTimestamp -> {
                    val dateTimeText = String.format("[%s %s]\n", nowDate, nowTime)
                    val memoBody = findViewById<EditText>(R.id.memoBody)
                    val memoBodyCursorEnd = memoBody.selectionEnd
                    memoBody.text.insert(memoBodyCursorEnd, dateTimeText)
                }
                R.id.btnInsertPos -> {
                    val posText = String.format("(%.5f, %.5f)\n", _latitude, _longitude)
                    val memoBody = findViewById<EditText>(R.id.memoBody)
                    val memoBodyCursorEnd = memoBody.selectionEnd
                    memoBody.text.insert(memoBodyCursorEnd, posText)
                }
                R.id.btnSave -> {
                    saveMemoToFile()
                }
            }
        }
    }

    // チェックボックスのイベントリスナ
    private inner class CheckBoxListener : View.OnClickListener {
        override fun onClick(v: View) {
            when (v.id) {
               R.id.gpsEnable -> {
                   val checkBox = findViewById<CheckBox>(R.id.gpsEnable)
                   if (checkBox.isChecked) {
                       // 位置情報利用の許可をユーザに求める
                       requestPermissionOfLocation()
                   } else {
                       // 位置情報の計測を停止
                       _fusedLocationClient.removeLocationUpdates(_onUpdateLocation)
                   }
               }
            }
        }
    }

    // 画面の日付・時刻表示を更新
    private fun getAndViewDateTimeText() {
        val nowDateTime = LocalDateTime.now()
        val dformatter = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)")
        val tformetter = DateTimeFormatter.ofPattern("HH:mm:ss")

        val dtext = nowDateTime.format(dformatter)
        val ttext = nowDateTime.format(tformetter)

        val nowDateTextView = findViewById<TextView>(R.id.nowDate)
        val nowTimeTextView = findViewById<TextView>(R.id.nowTime)

        nowDateTextView.text = dtext
        nowTimeTextView.text = ttext

        nowDate = dtext
        nowTime = ttext
    }

    // 位置情報利用の許可をユーザに求める
    private fun requestPermissionOfLocation() {
        // 位置情報の計測を開始
        if (ActivityCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            ActivityCompat.requestPermissions(this@MainActivity, permissions, 1000)
            return
        }
        _fusedLocationClient.requestLocationUpdates(_locationRequest, _onUpdateLocation, mainLooper)
    }

    // 外部ストレージ利用の許可をユーザに求める
    private fun requestPermissionOfStorageReadWrite() {
        if (ActivityCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
            val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            ActivityCompat.requestPermissions(this@MainActivity, permissions, 1010)
        }
    }

    // 権限許可ダイアログ応答処理
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // 位置情報利用許可されていたら
        if (requestCode == 1000) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[1] == PackageManager.PERMISSION_GRANTED
            ) {
                // 再度位置情報利用許可のチェックをし、許可されていなかったら処理を中止
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // 位置情報利用が許可されていない旨トースト表示
                    Toast.makeText(
                        this@MainActivity,
                        R.string.location_access_not_permitted,
                        Toast.LENGTH_SHORT
                    ).show()
                    // チェックボックスを未チェック状態に設定
                    val gpsCheckBox = findViewById<CheckBox>(R.id.gpsEnable)
                    gpsCheckBox.isChecked = false
                    return
                }

                // 位置情報の計測を開始
                _fusedLocationClient.requestLocationUpdates(
                    _locationRequest,
                    _onUpdateLocation,
                    mainLooper
                )
            } else {
                // 位置情報利用が許可されていない旨トースト表示
                Toast.makeText(
                    this@MainActivity,
                    R.string.location_access_not_permitted,
                    Toast.LENGTH_SHORT
                ).show()
                // チェックボックスを未チェック状態に設定
                val gpsCheckBox = findViewById<CheckBox>(R.id.gpsEnable)
                gpsCheckBox.isChecked = false
                return
            }
        } else if (requestCode == 1010) {
            // ストレージ権限の許可
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                allowed_access_to_storage = true
            }
        }
    }

    // 現在位置を地図で表示（暗黙的インテント）
    private fun onViewCurrentPosOnMapButtonClick() {
        val uriStr = "geo:${_latitude},${_longitude}"
        val uri = Uri.parse(uriStr)
        val intent = Intent(Intent.ACTION_VIEW, uri)

        startActivity(intent)
    }

    // ファイルに保存する
    private fun saveMemoToFile() {
        if (_saveFileName.isEmpty()) {
            // デフォルトのファイル名を生成
            val path = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).toString()
            // val nowDateTime = LocalDateTime.now()
            // val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHMMSS")
            // val dateTimeString = nowDateTime.format(formatter)
            // _saveFileName = "${path}/${R.string.default_save_filename_prefix}_${dateTimeString}.txt"

            _saveFileName = "${R.string.default_save_filename}"
        }

        // 将来対応：外部ストレージへのファイル保存
        // val launcher = registerForActivityResult(
        //     ActivityResultContracts.CreateDocument("text/plain")
        // ) { uri ->
        //     Toast.makeText(this@MainActivity, "url=${uri}", Toast.LENGTH_LONG).show()

        //     uri?.let {
        //         _saveFileName = it.path.toString()
        //         val file = it.path?.let { it1 -> File(it1) }

        //         val bwriter = BufferedWriter(FileWriter(file, false))
        //         val memoBody = findViewById<EditText>(R.id.memoBody)
        //         val memoText = memoBody.text

        //         bwriter.append(memoText)
        //         bwriter.newLine()
        //         bwriter.close()

        //         // 書き込み完了のトースト表示
        //         Toast.makeText(this@MainActivity, R.string.file_write_finished, Toast.LENGTH_LONG).show()
        //         Log.d("WRITE PATH", _saveFileName)
        //     }
        // }

        // ファイル保存処理
        // 読み書き可能ならば保存
        var writable = false
        val state = Environment.getExternalStorageState()
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            writable = true
        } else {
            // ストレージ読み書きの権限を求める
            requestPermissionOfStorageReadWrite()
            if (allowed_access_to_storage) {
                writable = true
            }
        }

        if (writable) {
            val bwriter = BufferedWriter(FileWriter(_saveFileName, false))
            val memoBody = findViewById<EditText>(R.id.memoBody)
            val memoText = memoBody.text

            bwriter.append(memoText)
            bwriter.newLine()
            bwriter.close()

            // 書き込み完了のトースト表示
            Toast.makeText(this@MainActivity, R.string.file_write_finished, Toast.LENGTH_LONG).show()
            Log.d("WRITE PATH", _saveFileName)
        } else {
            // ストレージアクセス権限がない旨のトースト表示
            Toast.makeText(this@MainActivity, R.string.file_access_not_allowed, Toast.LENGTH_LONG).show()
        }
    }

    // ファイルから読み込む
    private fun loadMemoFromFile() {
        // 将来対応：読み込み元ファイル名を設定できるようにする
        if (_saveFileName.isEmpty()) {
            // デフォルトのファイル名を生成
            val path = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).toString()
            // val nowDateTime = LocalDateTime.now()
            // val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHMMSS")
            // val dateTimeString = nowDateTime.format(formatter)
            // _saveFileName = "${path}/${R.string.default_save_filename_prefix}_${dateTimeString}.txt"

            _saveFileName = "${path}/${R.string.default_save_filename}"
        }

        // 将来対応：外部ストレージからのファイル読み込み

        // ファイル読み込み処理
        // 読み書き可能ならば読み込み、カーソルを最終位置にセットする
        val state = Environment.getExternalStorageState()
        val path = Paths.get(_saveFileName)
        if (Environment.MEDIA_MOUNTED.equals(state) && Files.exists(path)) {
            val breader = BufferedReader(FileReader(_saveFileName))
            val data = breader.readText()
            val memoBody = findViewById<EditText>(R.id.memoBody)
            memoBody.setText(data)
            memoBody.setSelection(memoBody.text.length)

            // 読み込み完了のトースト表示
            Toast.makeText(this@MainActivity, R.string.file_read_finished, Toast.LENGTH_SHORT).show()
        } else {
            // ストレージアクセス権限がない旨のトースト表示
            Toast.makeText(this@MainActivity, R.string.file_access_not_allowed, Toast.LENGTH_LONG).show()
        }
    }

}