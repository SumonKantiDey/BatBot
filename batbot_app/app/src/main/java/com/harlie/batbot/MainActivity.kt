package com.harlie.batbot

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProviders
import com.harlie.batbot.ui.bluetooth.BluetoothFragment
import com.harlie.batbot.ui.bluetooth.Bluetooth_ViewModel


class MainActivity : AppCompatActivity() {
    val TAG = "LEE: <" + MainActivity::class.java.getName() + ">";

    val REQUEST_ENABLE_BLUETOOTH = 1

    private lateinit var m_bluetoothViewModel: Bluetooth_ViewModel


    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        m_bluetoothViewModel = ViewModelProviders.of(this).get(Bluetooth_ViewModel::class.java)
        if (0 <= m_bluetoothViewModel.initializeDeviceList(this)) {
            val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBluetoothIntent, REQUEST_ENABLE_BLUETOOTH)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode)
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == Activity.RESULT_OK) {
                if (m_bluetoothViewModel.m_bluetoothAdapter!!.isEnabled) {
                    Log.i(TAG, "Bluetooth Enabled")
                    Toast.makeText(this@MainActivity,  "Bluetooth Enabled", Toast.LENGTH_LONG).show()

                    //--------------------------------------------------
                    Log.i(TAG, "load the BluetoothFragment")
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.container, BluetoothFragment.getInstance())
                        .commitNow()
                    //--------------------------------------------------

                } else {
                    Log.i(TAG, "Bluetooth Disabled")
                    Toast.makeText(this@MainActivity,  "Bluetooth Disabled", Toast.LENGTH_LONG).show()
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Log.i(TAG, "Bluetooth Canceled")
                Toast.makeText(this@MainActivity,  "BlueTooth Canceled", Toast.LENGTH_LONG).show()
            }
        }
    }
}
