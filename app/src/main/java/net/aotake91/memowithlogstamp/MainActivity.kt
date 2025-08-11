package net.aotake91.memowithlogstamp

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.core.net.toUri
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val timer = object : Runnable {
        // 日時更新タスク（1秒間隔）
        override fun run() {
            getAndViewDateTimeText()
            handler.postDelayed(this, 1000)
        }
    }

    // 自動保存タスク（30秒ごとに確認）
    private val autoSaveTimer = object : Runnable {
        override fun run() {
            saveMemoToDefaultFile()
            handler.postDelayed(this, 30000)
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
    // 保存ファイル先Uri（ファイル名を含むパス全体）
    private var _saveFileUri : Uri? = null
    // ストレージアクセス許可の有無
    private var allowedAccessToStorage = false
    // メモ本体の入力ボックステキストが更新されたか
    private var memoModified = false

    // メモ保存先のUri取得（名前を付けて保存）
    private val newFileSaveLauncher : ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val resultUriIntent = result.data
            val savefilePath = resultUriIntent?.data

            savefilePath?.let {
                exportMemoToFile(it)
                _saveFileUri = it
                // 上書きエクスポートボタンの有効化
                val btnOverwriteExport = findViewById<Button>(R.id.btnOverwriteExport)
                btnOverwriteExport.isEnabled = true
                btnOverwriteExport.setBackgroundColor(getColor(R.color.red))
            }
        }
    }

    // メモ保存先のUri取得（既存ファイル選択）
    private val alreadyExistsFileSaveLauncher : ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val resultUriIntent = result.data
            val savefilePath = resultUriIntent?.data

            savefilePath?.let { uri ->
                _saveFileUri = uri

                // ファイル名をUriから取り出して反映
                val contentResolver = applicationContext.contentResolver
                val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
                val cursor = contentResolver.query(uri, projection, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    _saveFileName = cursor.getString(name)
                }

                // ファイルを読み込む
                loadMemoFromFile(uri)

                // 上書きエクスポートボタンの有効化
                val btnOverwriteExport = findViewById<Button>(R.id.btnOverwriteExport)
                btnOverwriteExport.isEnabled = true
                btnOverwriteExport.setBackgroundColor(getColor(R.color.red))
            }
        }
    }

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
                lat.text = String.format(Locale.US, "%.5f", _latitude)
                lng.text = String.format(Locale.US, "%.5f", _longitude)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 日時更新を定期タスクとして登録
        handler.post(timer)

        // メモ本体テキストボックス内容変更イベント処理を登録
        val memoBody = findViewById<EditText>(R.id.memoBody)
        memoBody.doAfterTextChanged {
            memoModified = true
        }

        // 位置情報関係オブジェクトの登録
        _fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@MainActivity)
        val builder = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000)
        _locationRequest = builder.build()
        _onUpdateLocation = OnUpdateLocation()

        // 各ボタンにイベントリスナを登録
        val btnInsertTimestamp = findViewById<Button>(R.id.btnInsertTimestamp)
        val btnInsertPos = findViewById<Button>(R.id.btnInsertPos)
        val btnNewExport = findViewById<Button>(R.id.btnNewExport)
        val btnLoadFile = findViewById<Button>(R.id.btnLoadFile)
        val btnOverwriteExport = findViewById<Button>(R.id.btnOverwriteExport)
        val btnMapView = findViewById<Button>(R.id.btnViewCurrentPosOnMap)
        val buttonListener = ButtonListener()

        btnInsertTimestamp.setOnClickListener(buttonListener)
        btnInsertPos.setOnClickListener(buttonListener)
        btnNewExport.setOnClickListener(buttonListener)
        btnLoadFile.setOnClickListener(buttonListener)
        btnOverwriteExport.setOnClickListener(buttonListener)
        btnMapView.setOnClickListener(buttonListener)

        // 上書きエクスポートボタンは初期値で無効化
        btnOverwriteExport.isEnabled = false
        btnOverwriteExport.setBackgroundColor(getColor(R.color.gray))

        // 位置情報計測ボタンにイベントリスナを登録
        val gpsEnableToggleButton = findViewById<ToggleButton>(R.id.gpsEnable)
        val toggleButtonListener = ToggleButtonListener()
        gpsEnableToggleButton.setOnClickListener(toggleButtonListener)

        // ストレージアクセス許可の確認（権限リクエスト）
        requestPermissionOfStorageReadWrite()

        // メモファイルのロード（存在する場合）
        loadMemoFromDefaultFile()
        // 自動保存を定期タスクとして登録
        handler.post(autoSaveTimer)
    }

    override fun onResume() {
        super.onResume()
        val gpsEnableToggleButton = findViewById<ToggleButton>(R.id.gpsEnable)
        // 位置情報計測が有効化されている場合
        if (gpsEnableToggleButton.isChecked) {
            // 位置情報利用の再開（許可されていない場合は再度許可を求める）
            requestPermissionOfLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        // 位置情報の計測を停止
        _fusedLocationClient.removeLocationUpdates(_onUpdateLocation)
        // 現在のメモを内部ストレージに保存
        saveMemoToDefaultFile()
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
                    val posText = String.format(Locale.US, "(%.5f, %.5f)\n", _latitude, _longitude)
                    val memoBody = findViewById<EditText>(R.id.memoBody)
                    val memoBodyCursorEnd = memoBody.selectionEnd
                    memoBody.text.insert(memoBodyCursorEnd, posText)
                }
                R.id.btnNewExport -> {
                    selectAndExportMemoToFile()
                }
                R.id.btnLoadFile -> {
                    selectAndLoadAlreadyExistsExportFile()
                }
                R.id.btnOverwriteExport -> {
                    _saveFileUri?.let { uri -> exportMemoToFile(uri) }
                }
            }
        }
    }

    // 位置情報取得切替ボタンのイベントリスナ
    private inner class ToggleButtonListener : View.OnClickListener {
        override fun onClick(v: View) {
            when (v.id) {
               R.id.gpsEnable -> {
                   val toggleButton = findViewById<ToggleButton>(R.id.gpsEnable)
                   if (toggleButton.isChecked) {
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
                    val gpsEnableToggleButton = findViewById<ToggleButton>(R.id.gpsEnable)
                    gpsEnableToggleButton.isChecked = false
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
                val gpsEnableToggleButton = findViewById<ToggleButton>(R.id.gpsEnable)
                gpsEnableToggleButton.isChecked = false
                return
            }
        } else if (requestCode == 1010) {
            // ストレージ権限の許可
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                allowedAccessToStorage = true
            }
        }
    }

    // 現在位置を地図で表示（暗黙的インテント）
    private fun onViewCurrentPosOnMapButtonClick() {
        val uriStr = "geo:${_latitude},${_longitude}"
        val uri = uriStr.toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri)

        startActivity(intent)
    }

    // ファイルを開く（既存ファイル）
    private fun selectAndLoadAlreadyExistsExportFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "text/plain"

        alreadyExistsFileSaveLauncher.launch(intent)
    }

    // エクスポート先を選択し、メモを書き出す（新規作成）
    private fun selectAndExportMemoToFile() {
        // デフォルトのファイル名を生成
        val nowDateTime = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHMMSS")
        val dateTimeString = nowDateTime.format(formatter)
        _saveFileName = getString(R.string.default_save_filename_prefix) + "_" + dateTimeString + ".txt"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TITLE, _saveFileName)

        newFileSaveLauncher.launch(intent)
    }

    // ファイルに保存する（他のアプリと共有できるストレージへのエクスポート）
    private fun exportMemoToFile(fileUri : Uri) {
        val contentResolver = applicationContext.contentResolver
        try {
            contentResolver.openFileDescriptor(fileUri, "w")?.use { f ->
                FileOutputStream(f.fileDescriptor).use { writer ->
                    val memoBody = findViewById<EditText>(R.id.memoBody)
                    val memoText = memoBody.text
                    writer.write(memoText.toString().toByteArray())

                    // 書き込み完了のトースト表示
                    Toast.makeText(
                        this@MainActivity,
                        R.string.memo_export_finished,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            // エクスポートに失敗した場合はその旨のダイアログボックスを表示する
            Log.d("exportMemoToFile()", e.toString())
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.error_dialog_export_failed_title)
                .setMessage(R.string.error_dialog_export_failed_message)
                .setPositiveButton("OK") { _, _ -> }
                .show()
        }
    }

    // 選択したファイルから読み込む
    private fun loadMemoFromFile(fileUri : Uri) {
        val contentResolver = applicationContext.contentResolver
        try {
            contentResolver.openInputStream(fileUri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val text = reader.readText()
                    val memoBody = findViewById<EditText>(R.id.memoBody)
                    memoBody.setText(text)

                    if (memoBody.text.isNotEmpty()) {
                        memoBody.setSelection(memoBody.text.length - 1)
                    }

                    // テキストボックスへデータを入れた時に変更済みイベントが発生するが、
                    // メモ本体のテキストボックス内容が更新されていない扱いとする
                    memoModified = false

                    // 読み込み完了のトースト表示
                    Toast.makeText(
                        this@MainActivity,
                        R.string.file_load_finished,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            // ファイル読み込み時に例外が発生したら読み込みができなかった旨のダイアログ表示
            Log.d("loadMemoFromFile()", e.toString())
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.error_dialog_load_file_failed_title)
                .setMessage(R.string.error_dialog_load_file_failed_message)
                .setPositiveButton("OK") { _, _ -> }
                .show()
        }
    }

    // デフォルトのファイル（バックアップ用）から読み込む
    private fun loadMemoFromDefaultFile() {
        // ファイル読み込み処理
        // 読み書き可能ならば読み込み、カーソルを最終位置にセットする
        try {
            val state = Environment.getExternalStorageState()
            val dirpath = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).toString()
            val filepath = dirpath + getString(R.string.default_save_filename)

            if (Environment.MEDIA_MOUNTED == state && Files.exists(Paths.get(filepath))) {
                BufferedReader(FileReader(filepath)).use { reader ->
                    val data = reader.readText()
                    val memoBody = findViewById<EditText>(R.id.memoBody)
                    memoBody.setText(data)

                    if (memoBody.text.isNotEmpty()) {
                        memoBody.setSelection(memoBody.text.length - 1)
                    }

                    // テキストボックスへデータを入れた時に変更済みイベントが発生するが、
                    // メモ本体のテキストボックス内容が更新されていない扱いとする
                    memoModified = false

                    // 読み込み完了のトースト表示
                    Toast.makeText(
                        this@MainActivity,
                        R.string.file_autoload_finished,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                // 読み込みができなかった旨のトースト表示
                Toast.makeText(this@MainActivity, R.string.could_not_autoload, Toast.LENGTH_SHORT)
                    .show()
            }
        } catch (e : Exception) {
            // ファイル読み込み時に例外が発生したら単に読み込まず進む
            Log.d("loadMemoFromDefaultFile()", e.toString())
            // 読み込みができなかった旨のトースト表示
            Toast.makeText(this@MainActivity, R.string.could_not_autoload, Toast.LENGTH_SHORT)
                .show()
        }
    }

    // デフォルトのファイル（バックアップ用）への保存
    private fun saveMemoToDefaultFile() {
        // 読み書き可能であるかの判定
        var writable = false
        val state = Environment.getExternalStorageState()
        if (Environment.MEDIA_MOUNTED == state) {
            writable = true
        } else {
            // ストレージ読み書きの権限を求める
            requestPermissionOfStorageReadWrite()
            if (allowedAccessToStorage) {
                writable = true
            }
        }

        // 読み書き可能、かつ自動保存の必要があるかを判定
        if (writable) {
            if (memoModified) {
                try {
                    val dirpath = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).toString()
                    val filepath = dirpath + getString(R.string.default_save_filename)

                    BufferedWriter(FileWriter(filepath, false)).use { writer ->
                        val memoBody = findViewById<EditText>(R.id.memoBody)
                        val memoText = memoBody.text

                        writer.append(memoText)

                        // メモ本体のテキストボックス内容が更新されていない扱いとする
                        memoModified = false
                    }
                } catch (e : Exception) {
                    // 自動保存時に例外が発生したら保存をせずに単に進む
                    Log.d("saveMemoFromDefaultFile()", e.toString())
                    // 読み込みができなかった旨のトースト表示
                    Toast.makeText(this@MainActivity, R.string.could_not_autosave, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        } else {
            // 自動保存ができなかった旨のトースト表示
            Toast.makeText(this@MainActivity, R.string.could_not_autosave, Toast.LENGTH_SHORT).show()
        }
    }
}