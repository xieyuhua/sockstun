/*
 ============================================================================
 文件名  : BaseActivity.java
 说明    : 所有 Activity 的基类：在 super.onCreate() 之前套用所选主题。
 ============================================================================
*/

package com.tunvpn;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public abstract class BaseActivity extends AppCompatActivity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		ThemeManager.applyTheme(this);
		super.onCreate(savedInstanceState);
		/* 为当前进程绑定共享日志文件（cache/tproxy.log）：这样没有 Service 句柄的代码
		   —— CoreTestHost、以及跑在 App 进程里的测速 —— 也能写进「日志」页读的那份文件。
		   :native 进程里的 TProxyService 做同样的事。 */
		TestLog.init(this);
	}
}
