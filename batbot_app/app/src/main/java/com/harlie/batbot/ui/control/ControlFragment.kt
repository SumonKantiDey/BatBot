// Copyright (c) 2019, Lee Hounshell. All rights reserved.

package com.harlie.batbot.ui.control

import android.app.Dialog
import android.bluetooth.BluetoothDevice
import android.content.ContentValues
import android.content.Context.LAYOUT_INFLATER_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableBoolean
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.daimajia.numberprogressbar.OnProgressBarListener
import com.harlie.batbot.BluetoothActivity
import com.harlie.batbot.ControlActivity.Companion.STORAGE_PERMISSION_REQUEST
import com.harlie.batbot.Manifest
import com.harlie.batbot.R
import com.harlie.batbot.databinding.ControlFragmentBinding
import com.harlie.batbot.event.BluetoothMessageEvent
import com.harlie.batbot.event.BluetoothStateChangeEvent
import com.harlie.batbot.event.BluetoothStatusEvent
import com.harlie.batbot.event.ImageDownloadProgressBarEvent
import com.harlie.batbot.model.RobotCommandModel
import com.harlie.batbot.service.BluetoothChatService
import com.harlie.batbot.service.Constants
import com.harlie.batbot.util.*
import kotlinx.android.synthetic.main.control_fragment.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.*
import kotlin.concurrent.fixedRateTimer


class ControlFragment : Fragment(), OnProgressBarListener  {
    val TAG = "LEE: <" + ControlFragment::class.java.name + ">"

    companion object {
        val instance = ControlFragment()
        fun getInstance(): Fragment {
            return instance
        }
    }

    val MAX_WAIT_LOG_DATA = 30000
    val BLUEDOT_SAMPLE_RATE_MILLIS = 333
    val IMAGE_FILE_HEADER = "File: /"
    val IMAGE_SIZE_HEADER = "Size: /"
    val I_SEE_SOMETHING   = "I see: "

    val m_robotConnection = ObservableBoolean(false)
    val m_downloadingNow  = ObservableBoolean(false)

    private var m_robotCommand = RobotCommandModel("", "")
    private var m_logging = LoggingTextTail()
    private var m_timer_ok = false
    private var m_havePing = false
    private var m_fixedTimerLoopCount = 0
    private var m_pingStartTime = System.currentTimeMillis()
    private var m_joystickTime = System.currentTimeMillis()

    private lateinit var m_ControlFragBinding : ControlFragmentBinding
    private var m_ControlViewModel: Control_ViewModel? = null
    private lateinit var m_fixedTimer: Timer
    private var m_settingsDialog: Dialog? = null

    private var m_name: String? = null
    private var m_address: String? = null
    private var m_device: BluetoothDevice? = null
    private var m_uniqueId: String? = null

    private var m_last_x: Double = 0.0
    private var m_last_y: Double = 0.0

    private var m_captureImage: ImageView? = null
    private var m_captureImageInfo: TextView? = null
    private var m_captureFilename: TextView? = null
    private var m_captureIdentifyText: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        @Nullable container: ViewGroup?,
        @Nullable savedInstanceState: Bundle?
    ): View? {
        Log.d(TAG, "onCreateView")
        m_ControlFragBinding = DataBindingUtil.inflate(
            inflater, com.harlie.batbot.R.layout.control_fragment, container, false
        )
        m_ControlFragBinding.robotCommand = m_robotCommand
        m_ControlFragBinding.robotConnection = m_robotConnection
        m_ControlFragBinding.downloadingNow = m_downloadingNow
        m_ControlFragBinding.lifecycleOwner = viewLifecycleOwner
        m_ControlFragBinding.executePendingBindings()

        val view = m_ControlFragBinding.root
        return view
    }

    fun setDeviceInfo(name: String, address: String, device: BluetoothDevice, uniqueId: String) {
        Log.d(TAG, "setDeviceInfo: name=" + name + ", address=" + address + ", uniqueId=" + uniqueId)
        this.m_name = name
        this.m_address = address
        this.m_device = device
        this.m_uniqueId = uniqueId
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        Log.d(TAG, "onActivityCreated")
        super.onActivityCreated(savedInstanceState)
        m_ControlViewModel = getViewModel()
        m_ControlViewModel!!.initialize()

        m_ControlViewModel!!.getInputCommand().observe(this, Observer {
            it?.let {
                Log.d(TAG, "observe getInputCommand() ===> it.m_robotCommand=" + it.robotCommand)
                m_robotCommand.robotCommand = it.robotCommand
                m_robotCommand.commandPriority = it.commandPriority
                send(m_robotCommand.robotCommand) // send command to the robot
                m_ControlFragBinding.robotCommand?.notifyChange()
            }
        })

        m_ControlViewModel!!.getTextOutputClicked().observe(this, Observer {
            it?.let {
                Log.d(TAG, "observe getTextOutputClicked() ===> " + it)
                m_robotCommand.robotCommand = textOutput.text.toString()
                send(m_robotCommand.robotCommand) // send command to the robot
                m_ControlFragBinding.robotCommand?.notifyChange()
            }
        })

        m_ControlViewModel!!.getStarClicked().observe(this, Observer {
            it?.let {
                Log.d(TAG, "observe getStarClicked()===> * clicked=" + it)
                send("click: *")
            }
        })

        m_ControlViewModel!!.getOkClicked().observe(this, Observer {
            it?.let {
                Log.d(TAG, "observe getOkClicked()===> ok clicked=" + it)
                send("click: ok")
            }
        })

        m_ControlViewModel!!.getSharpClicked().observe(this, Observer {
            it?.let {
                Log.d(TAG, "observe getSharpClicked()===> # clicked=" + it)
                send("click: #")
            }
        })

        m_ControlViewModel!!.getCaptureImage().observe(this, Observer {
            it?.let {
                Log.d(TAG, "observe getCaptureImage()===> " + it)
                // display image popup
                activity!!.runOnUiThread {
                    Log.d(TAG, "create IMAGE POPUP Dialog")
                    m_settingsDialog = Dialog(context!!, R.style.Theme_Dialog)
                    m_settingsDialog!!.window?.requestFeature(Window.FEATURE_NO_TITLE)
                    m_settingsDialog!!.getWindow().setBackgroundDrawableResource(android.R.color.transparent)
                    val inflater: LayoutInflater = activity!!.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
                    val lines = m_captureIdentifyText!!.split('\n')
                    Log.d(TAG, "number of lines in text is " + lines.count())
                    val isSimpleCapture: Boolean = lines.count() <= 3
                    var view: View? = null
                    if (isSimpleCapture) {
                        Log.d(TAG, "inflate R.layout.capture_image")
                        view = inflater.inflate(R.layout.capture_image, null)
                        m_settingsDialog!!.setContentView(view)
                        m_captureImageInfo = null
                    }
                    else { // we are identifying the content of an image
                        Log.d(TAG, "inflate R.layout.capture_and_train_image")
                        view = inflater.inflate(R.layout.capture_and_train_image, null)
                        m_settingsDialog!!.setContentView(view)
                        m_captureImageInfo = view.findViewById<TextView>(R.id.capture_info) as TextView
                        m_captureImageInfo!!.text = m_captureIdentifyText
                    }
                    m_captureImage = view.findViewById<ImageView>(R.id.capture_image) as ImageView
                    m_captureImage!!.setImageBitmap(it.bitMap)
                    m_captureFilename = view.findViewById<TextView>(R.id.capture_filename) as TextView
                    m_captureFilename!!.text = it.name // trick to pass filename data for a 'Save' click
                    m_captureFilename!!.visibility = View.GONE
                    m_settingsDialog!!.setCancelable(true)
                    m_settingsDialog!!.setCanceledOnTouchOutside(true)
                    Log.d(TAG, "display POPUP IMAGE Dialog")
                    m_settingsDialog!!.show() // POPUP IMAGE DIALOG
                }
                Log.d(TAG, "restarting the fixed-rate Timer")
                m_timer_ok = true
                runFixedRateTimer()
            }
        })

        disableButtons()
        connect()
    }

    fun addImageToGallery(filePath: String) {
        Log.d(TAG, "addImageToGallery")
        val values: ContentValues = ContentValues()
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        values.put(MediaStore.MediaColumns.DATA, filePath)
        context!!.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun saveImageToInternalStorage(drawable: BitmapDrawable, fileName: String) : Uri {
        Log.d(TAG, "saveImageToInternalStorage: fileName=" + fileName)
        //val bitmap: Bitmap = draw.getBitmap() as Bitmap
        val bitmap = (drawable as BitmapDrawable).bitmap

        val sdCard: File = Environment.getExternalStorageDirectory()
        val dir = File(sdCard.getAbsolutePath() + "/BatBot_images")
        dir.mkdirs()

        val imageFile = File(dir, fileName)
        try {
            val stream: OutputStream = FileOutputStream(imageFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            stream.flush()
            stream.close()
            Log.d(TAG, "SUCCESS: saved image to " + imageFile.absolutePath)
            activity?.runOnUiThread(java.lang.Runnable {
                Toast.makeText(context,  "IMAGE SAVED", Toast.LENGTH_LONG).show()
            })
        } catch (e: IOException){ // Catch the exception
            Log.d(TAG, "ERROR: unable to save image, e=" + e)
        }
        val fullPath = Uri.parse(imageFile.absolutePath)
        addImageToGallery(fullPath.path)
        return fullPath
    }

    fun saveImage() {
        Log.d(TAG, "saveImage")
        val bitmapDrawable: BitmapDrawable = m_captureImage!!.getDrawable() as BitmapDrawable
        var nanoFilename: String = m_captureFilename!!.text.toString().substringAfterLast("/")
        nanoFilename = nanoFilename.substring(0, nanoFilename.lastIndexOf('.'))
        val fileName: String = String.format("%s_%d.jpg", nanoFilename, System.currentTimeMillis())
        val uri = saveImageToInternalStorage(bitmapDrawable, fileName)
        Log.d(TAG, "SAVED IMAGE TO " + uri.path)
    }

    fun onClickSaveImage() {
        Log.d(TAG, "onClickSaveImage")
        val currentStoragePermission = ActivityCompat.checkSelfPermission(context!!, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (currentStoragePermission != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "onClickSaveImage: requestPermissions")
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
        }
        else {
            Log.d(TAG, "onClickSaveImage: have permissions")
            saveImage()
        }
        m_settingsDialog?.dismiss()
    }

    fun train() {
        Log.d(TAG, "train: m_captureIdentifyText=" + m_captureIdentifyText)
        val nanoFilename = m_captureIdentifyText!!.split('\n')[0].trim().substring(IMAGE_FILE_HEADER.length - 1)
        Log.d(TAG, "train: " + nanoFilename)
        val builder = AlertDialog.Builder(context!!)
        val inflater = layoutInflater
        builder.setTitle("Help Train BatBot")
        val dialogLayout = inflater.inflate(R.layout.correct_image_name, null)
        val editText: EditText = dialogLayout.findViewById<EditText>(R.id.correct_name)
        builder.setView(dialogLayout)
        builder.setPositiveButton(resources.getString(R.string.ok)) {
                dialog, which ->
            val text = editText.text.toString()
            Log.d(TAG, "CLICK: OK, text=" + text)
            dialog.dismiss()
            m_settingsDialog?.dismiss()
            send("\n@TRAIN '" + nanoFilename + "' learn='" + text + "'")
        }
        builder.setNegativeButton(resources.getString(R.string.cancel)) {
                dialog, which ->
            Log.d(TAG, "CLICK: Cancel")
            dialog.dismiss()
            m_settingsDialog?.dismiss()
        }
        builder.show()
    }

    fun onClickDismissImage() {
        Log.d(TAG, "onClickDismissImage")
        m_settingsDialog?.dismiss()
        var nanoFilename: String = m_captureFilename!!.text.toString()
        Log.d(TAG, "removeUnusedImage: " + nanoFilename)
        send("\n@DELETE " + nanoFilename)
        activity?.runOnUiThread(java.lang.Runnable {
            Toast.makeText(context,  "IMAGE DELETED", Toast.LENGTH_LONG).show()
        })
    }

    fun runFixedRateTimer() {
        Log.d(TAG, "runFixedRateTimer")
        if (::m_fixedTimer.isInitialized) {
            m_fixedTimer.cancel()
        }
        m_fixedTimer = fixedRateTimer("timer", true, 0L, 100) {
            if (m_timer_ok) {
                m_fixedTimerLoopCount += 1
                if ((m_fixedTimerLoopCount % 20) == 0) { // flush every 2 seconds
                    //Log.d(TAG, "runFixedRateTimer: FLUSH LOG")
                    send(" ")
                }
                activity!!.runOnUiThread {
                    if (logging != null) {
                        logging.text = m_logging.content()
                    }
                }
                val now = System.currentTimeMillis()
                if ((m_logging.lastLogTime() - now) > MAX_WAIT_LOG_DATA) {
                    m_timer_ok = false
                    weHaveAProblem("FROZEN BLUETOOTH!")
                }
            }
            else {
                m_fixedTimer.cancel()
            }
        }
    }

    override fun onStart() {
        Log.d(TAG, "onStart")
        super.onStart()
        EventBus.getDefault().register(this)
        m_timer_ok = true
    }

    override fun onResume() {
        Log.d(TAG, "onStart")
        super.onResume()
        runFixedRateTimer()
    }

    @Subscribe(threadMode = ThreadMode.MAIN) // from notify
    fun onBluetoothStateChangeEvent(bt_event: BluetoothStateChangeEvent) {
        //Log.d(TAG, "onBluetoothStateChangeEvent: theState=" + bt_event.theState + ", whatChanged=" + bt_event.whatChanged)
        when(bt_event.whatChanged) {

            Constants.MESSAGE_STATE_CHANGE -> {
                when (bt_event.theState) {
                    BluetoothChatService.STATE_CONNECTING -> {
                        Log.d("status", "connecting")
                    }
                    BluetoothChatService.STATE_CONNECTED -> {
                        Log.d("status", "connected")
                    }
                    BluetoothChatService.STATE_LISTEN, BluetoothChatService.STATE_NONE -> {
                        Log.d("status", "not connected")
                    }
                }
            }

            Constants.MESSAGE_WRITE -> {
                val writeBuf = bt_event.extra.getByteArray(Constants.DATA) as ByteArray
                val bytesSent = bt_event.extra.getInt(Constants.SIZE)
                // construct a string from the buffer
                val writeMessage = String(writeBuf)
                //Log.d(TAG, "--> SENT $bytesSent BYTES: $writeMessage")
            }

            Constants.MESSAGE_READ -> {
                val readBuf = bt_event.extra.getByteArray(Constants.DATA) as ByteArray
                val bytesRead = bt_event.extra.getInt(Constants.SIZE)
                // construct a string from the valid bytes in the buffer
                val readData = String(readBuf, 0, bytesRead)
                // message received
                //Log.d(TAG, "--> READ $bytesRead BYTES: $readData")
                m_logging.append(readData)
                if (readData.length > 0) {
                    logging.text = m_logging.content()

                    if (m_havePing == false) {
                        if (readData.contains("ping")) {
                            m_havePing = true
                            Log.d(TAG, "--> got ping reply <--")
                        } else {
                            Log.d(TAG, "no ping reply yet")
                            val now = System.currentTimeMillis()
                            if ((m_pingStartTime - now) > 10000) {
                                weHaveAProblem("ping failed")
                            }
                        }
                    }
                }
            }

            Constants.IMAGE_READ -> {
                Log.d(TAG, "===> IMAGE_READ <===")
            }

            Constants.MESSAGE_DEVICE_NAME -> {
                // save the connected device's name
                val connectedDeviceName = bt_event.extra.getString(Constants.DEVICE_NAME)
                Log.d(TAG, "===> connectedDeviceName=" + connectedDeviceName!!)
                enableButtons()
                m_ControlViewModel?.doClickOk()
            }

            Constants.MESSAGE_TOAST -> {
                val message = bt_event.extra.getString(Constants.TOAST)
                Log.d(TAG, "===> TOAST message=" + message!!)
                activity?.runOnUiThread(java.lang.Runnable {
                    Toast.makeText(context,  message, Toast.LENGTH_LONG).show()
                })
            }
        }
    }

    private fun disableButtons() {
        Log.d(TAG, "disableButtons")
        m_robotConnection.set(false)
    }

    private fun enableButtons() {
        Log.d(TAG, "enableButtons")
        clearTextViews()
        m_robotConnection.set(true)
    }

    private fun downloadingNow() {
        Log.d(TAG, "downloadingNow")
        m_downloadingNow.set(true)
    }

    private fun downloadComplete() {
        Log.d(TAG, "downloadComplete")
        m_downloadingNow.set(false)
        activity?.runOnUiThread(java.lang.Runnable {
            Toast.makeText(context,  "IMAGE DOWNLOADED", Toast.LENGTH_LONG).show()
        })
    }

    fun clearTextViews() {
        Log.d(TAG, "initializeTextViews")
        if (textOutput != null) {
            textOutput.text = ""
            Log.d(TAG, "textOutput is empty")
        }
        if (logging != null) {
            logging.text = ""
            Log.d(TAG, "logging is empty")
        }
    }

    fun weHaveAProblem(theProblem: String) {
        Log.e(TAG, "===> weHaveAProblem: " + theProblem)
        activity?.runOnUiThread(java.lang.Runnable {
            Toast.makeText(context, theProblem.toUpperCase(), Toast.LENGTH_LONG).show()
        })
        disconnect()
        gotoBluetoothActivity()
    }

    @Subscribe(threadMode = ThreadMode.MAIN) // from notify
    fun onBluetoothStatusEvent(bt_status_event: BluetoothStatusEvent) {
        //Log.d(TAG, "onBluetoothStatusEvent: message=" + bt_status_event.message)
        msg(bt_status_event.message)
        if (bt_status_event.message.equals(Constants.DISCONNECT)) {
            weHaveAProblem(Constants.DISCONNECT)
        }
        else if (bt_status_event.message.equals(Constants.CONNECTION_FAILED)) {
            weHaveAProblem(Constants.CONNECTION_FAILED)
        }
        else if (bt_status_event.message.equals(Constants.CONNECTION_LOST)) {
            weHaveAProblem(Constants.CONNECTION_LOST)
        }
        else if (bt_status_event.message.equals(Constants.INITIALIZING)) {
            Log.d(TAG, "===> INITIALIZING <===")
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothMessageEvent(bt_message_event: BluetoothMessageEvent) {
        Log.d(TAG, "onBluetoothMessageEvent: message=" + bt_message_event.message)
        val builder = AlertDialog.Builder(context!!)
        with(builder)
        {
            setTitle(getString(R.string.message))
            if (bt_message_event.message.split('\n')[0].trim().startsWith(IMAGE_FILE_HEADER)) {
                Log.d(TAG, "ask to view the image")
                setMessage(bt_message_event.message + "\n\nView this Image?")
                builder.setPositiveButton("VIEW") {dialog, which ->
                    Log.d(TAG, "--> click 'VIEW'")
                    textOutput.visibility = View.INVISIBLE
                    uploadImageFor(bt_message_event)
                }
                builder.setNeutralButton("DELETE") { dialog, which ->
                    Log.d(TAG, "--> click 'DELETE'")
                    removeUnusedImage(bt_message_event)
                }
                try {
                    if (bt_message_event.message.split('\n')[3].trim().startsWith(I_SEE_SOMETHING)) {
                        setMessage(bt_message_event.message + "\n\nTrain or View this Image?")
                        builder.setNegativeButton("TRAIN") { dialog, which ->
                            Log.d(TAG, "--> click 'TRAIN'")
                            m_captureIdentifyText = bt_message_event.message
                            train()
                        }
                    }
                }
                catch (e: IndexOutOfBoundsException) {
                    // ignore it
                }
            }
            else {
                setMessage(bt_message_event.message)
                // Set a positive button and its click listener on alert dialog
                builder.setPositiveButton("OK") {dialog, which ->
                    Log.d(TAG, "--> click 'OK'")
                }
            }
            setCancelable(true)
            Log.d(TAG, "display POPUP TEXT Dialog")
            show() // POPUP TEXT DIALOG
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onProgressBarEvent(imageDownloadProgress_event: ImageDownloadProgressBarEvent) {
        Log.d(TAG, "onProgressBarEvent: progress=" + imageDownloadProgress_event.progress)
        if ((imageDownloadProgress_event.progress == 0)
        || (imageDownloadProgress_event.progress == imageDownloadProgress_event.max)) {
            Log.d(TAG, "hide number_progress_bar and restore textOutput")
            downloadComplete()
            textOutput.visibility = View.VISIBLE
        }
        else {
            number_progress_bar.max = imageDownloadProgress_event.max
            number_progress_bar.progress = imageDownloadProgress_event.progress
            downloadingNow()
        }
    }

    override fun onProgressChange(current: Int, max: Int) {
        Log.d(TAG, "onProgressChange: current=" + current + ", max=" + max)
    }

    private fun removeUnusedImage(btMessageEvent: BluetoothMessageEvent) {
        val image_file = btMessageEvent.message.split('\n')[0].trim().substring(IMAGE_FILE_HEADER.length - 1)
        Log.d(TAG, "removeUnusedImage: " + image_file)
        send("\n@DELETE " + image_file)
        activity?.runOnUiThread(java.lang.Runnable {
            Toast.makeText(context,  "IMAGE DELETED", Toast.LENGTH_LONG).show()
        })
    }

    private fun uploadImageFor(btMessageEvent: BluetoothMessageEvent) {
        Log.d(TAG, "uploadImageFor")
        try {
            m_captureIdentifyText = btMessageEvent.message
            val image_file = btMessageEvent.message.split('\n')[0].trim().substring(IMAGE_FILE_HEADER.length - 1)
            val image_size = btMessageEvent.message.split('\n')[1].trim().substring(IMAGE_SIZE_HEADER.length - 1).toInt()
            Log.d(TAG, "uploadImageFor: " + image_file + ", size: " + image_size)
            downloadingNow()
            m_timer_ok = false // STOP THE fixedRateTimer - it will be restarted after the image arrives
            m_ControlViewModel!!.uploadImage(image_file, image_size)
            send("\n@UPLOAD " + image_file + '\n')
        }
        catch (e: Exception) {
            Log.e(TAG, "uploadImageFor: " + btMessageEvent + " FAILED!")
            activity?.runOnUiThread(java.lang.Runnable {
                Toast.makeText(context,  "IMAGE UPLOAD FAILED!", Toast.LENGTH_LONG).show()
            })
            m_timer_ok = true
            runFixedRateTimer()
        }
    }

    override fun onStop() {
        Log.d(TAG, "onStop")
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    private fun getViewModel(): Control_ViewModel? {
        Log.d(TAG, "getViewModel")
        if (m_ControlViewModel == null) {
            activity?.let {
                m_ControlViewModel = ViewModelProviders.of(it).get(Control_ViewModel::class.java)
            }
        }
        return m_ControlViewModel
    }

    fun connect() {
        Log.d(TAG, "connect")
        if (m_device == null) {
            weHaveAProblem("NO DEVICE FOUND!")
        }
        else {
            // we need to initialize the bluetooth adapter
            m_ControlViewModel = getViewModel()
            m_ControlViewModel!!.initialize()

            // Attempt to connect to the device
            m_downloadingNow.set(false)
            m_robotConnection.set(false)
            m_havePing = false
            msg("connecting..")
            m_ControlViewModel!!.connect(m_device!!, true)

            msg(resources.getString(R.string.please_wait))
            // Once connected setup the listener
            setControlListener()
        }
    }

    private fun setControlListener() {
        Log.d(TAG, "setControlListener")
        bluedot_matrix.setOnUseListener(object : DynamicMatrix.DynamicMatrixListener {
            override fun onPress(
                cell: DynamicMatrix.MatrixCell,
                pointerId: Int,
                actual_x: Float,
                actual_y: Float
            ) {
                Log.d(TAG, "onPress: actual_x=" + actual_x + ", actual_y=" + actual_y)
                val x = calcX(cell, actual_x)
                val y = calcY(cell, actual_y)
                m_ControlFragBinding.bluedotMatrix.onPress(actual_x, actual_y)
                send(buildMessage("1", x, y))
                m_last_x = x
                m_last_y = y
                m_joystickTime = System.currentTimeMillis()
            }

            // throttle onMove to avoid flooding the Arduino with commands
            override fun onMove(
                cell: DynamicMatrix.MatrixCell,
                pointerId: Int,
                actual_x: Float,
                actual_y: Float
            ) {
                val now = System.currentTimeMillis()
                if ((now - m_joystickTime) > BLUEDOT_SAMPLE_RATE_MILLIS) {
                    Log.d(TAG, "onMove: actual_x=" + actual_x + ", actual_y=" + actual_y)
                    val x = calcX(cell, actual_x)
                    val y = calcY(cell, actual_y)
                    if (x != m_last_x || y != m_last_y) {
                        send(buildMessage("2", x, y))
                        m_ControlFragBinding.bluedotMatrix.onMove(actual_x, actual_y)
                        m_last_x = x
                        m_last_y = y
                    }
                    m_joystickTime = System.currentTimeMillis()
                } else {
                    Log.d(TAG, "onMove: IGNORED")
                    m_ControlFragBinding.bluedotMatrix.onMove(actual_x, actual_y)
                }
            }

            override fun onRelease(
                cell: DynamicMatrix.MatrixCell,
                pointerId: Int,
                actual_x: Float,
                actual_y: Float
            ) {
                Log.d(TAG, "onRelease: actual_x=" + actual_x + ", actual_y=" + actual_y)
                val x = calcX(cell, actual_x)
                val y = calcY(cell, actual_y)
                send(buildMessage("0", x, y))
                m_ControlFragBinding.bluedotMatrix.onRelease(actual_x, actual_y)
                m_last_x = x
                m_last_y = y
                m_joystickTime = System.currentTimeMillis()
            }
        })
    }

    private fun calcX(cell: DynamicMatrix.MatrixCell, actual_x: Float): Double {
        Log.d(TAG, "calcX")
        var relative_x = actual_x - cell.bounds.left
        relative_x = (relative_x - cell.width / 2) / (cell.width / 2)
        return Math.round(relative_x * 10000.0).toDouble() / 10000.0
    }

    private fun calcY(cell: DynamicMatrix.MatrixCell, actual_y: Float): Double {
        Log.d(TAG, "calcY")
        var relative_y = actual_y - cell.bounds.top
        relative_y = (relative_y - cell.height / 2) / (cell.height / 2) * -1
        return Math.round(relative_y * 10000.0).toDouble() / 10000.0
    }

    private fun buildMessage(operation: String, x: Double, y: Double): String {
        Log.d(TAG, "buildMessage")
        return "$operation,$x,$y"
    }

    fun send(message: String) {
        //Log.d(TAG, "send: " + message)
        var msg_tail = ""
        if (message.length == 0) {
            // so we send at least one character to kick-start reading the log data.
            // it will display on the nano log, so.. using space is least intrusive.
            msg_tail = " "
        }
        m_ControlViewModel?.send(message + msg_tail)
    }

    private fun msg(message: String) {
        Log.d(TAG, "msg: " + message)
        status.text = message
    }

    private fun disconnect() {
        Log.d(TAG, "disconnect")
        m_ControlViewModel?.disconnect()
    }

    fun gotoBluetoothActivity() {
        Log.d(TAG, "gotoBluetoothActivity")
        m_timer_ok = false
        val controlIntent: Intent = Intent(activity, BluetoothActivity::class.java)
        controlIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(controlIntent)
        activity!!.overridePendingTransition(0, R.anim.fade_out)
        activity!!.finish()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        disconnect()
        m_timer_ok = false
        if (::m_fixedTimer.isInitialized) {
            m_fixedTimer.cancel()
        }
        m_ControlViewModel = null
        super.onDestroy()
    }

}
