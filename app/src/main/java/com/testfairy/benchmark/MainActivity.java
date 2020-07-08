package com.testfairy.benchmark;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;

import com.testfairy.TestFairy;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Files;

import androidx.appcompat.app.AppCompatActivity;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;

/**
 * This activity showcases how to load TestFairy SDK classes into memory dynamically.
 * The SDK is added to the project as a compileOnly dependency. This means although
 * static type checking will work, none of the SDK classes will be available
 * during runtime, unless we ask for them. Please note that the SDK must manually be
 * added to the app's assets directory.
 *
 * You can download the latest SDK from https://bintray.com/testfairy/testfairy/testfairy/_latestVersion
 *
 * Make sure you download the JAR file, not the AAR.
 *
 * Run this command (replace x.y.z) in android SDK directory to convert the JAR file into its appropriate format.
 *     `build-tools/x.y.z/dx --dex --output testfairy.jar testfairy-android-sdk-1.11.5.jar`
 *
 * Put the newly created `testfairy.jar` into the assets directory.
 */
public class MainActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		begin(this, "APP_TOKEN");
	}

	/**
	 * A simple wrapper for TestFairy.begin(), loads the class into memory on demand instead of during app launch.
	 * @param context An Activity or Application context
	 * @param token Your TestFairy app token
	 */
	private static void begin(Context context, String token) {
		try {
			Class<?> testfairyClass = null;
			try {
				// Use this class if you prefer reflection to interact with the SDK.
				testfairyClass = loadTestFairyDynamically(context);

				// This line will work because we added the SDK to the default list of dex files.
				TestFairy.begin(context, token);
			} catch (NoClassDefFoundError e) {
				// If we reach here, it means the SDK is loaded but reflection is the only way to interact it.
				// This will happen if the device manufacturer disables the priviledges to modify default list of loaded dexs.
				if (testfairyClass != null) {
					testfairyClass.getDeclaredMethod("begin", Context.class, String.class)
							.invoke(null, context, token);
				}
			}
		} catch (Throwable t) {
			Log.e("MainActivity", "Error loading TestFairy jar", t);
		}
	}

	/**
	 * Finds 'testfairy.jar' in the assets and copy it into code cache directory
	 *
	 * @param context
	 * @return
	 * @throws ClassNotFoundException
	 * @throws IOException
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 */
	private static Class<?> loadTestFairyDynamically(Context context)
			throws ClassNotFoundException, IOException, NoSuchFieldException, IllegalAccessException {
		// Decide where to store dex files
		File codeCacheDir = context.getApplicationContext().getCodeCacheDir();
		String internalPath = codeCacheDir.getAbsolutePath() + File.separator + "testfairy." + BuildConfig.VERSION_CODE + ".jar";
		File desFile = new File(internalPath);

		// If this is the first time we load TestFairy for this build, delete old SDKs and copy the new one to the cache
		if (!desFile.exists()) {
			File[] fairies = codeCacheDir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.contains("testfairy");
				}
			});

			for (File oldJar : fairies) {
				oldJar.delete();
			}

			AssetManager am = context.getAssets();
			InputStream originalStream = am.open("testfairy.jar");

			Files.copy(originalStream, desFile.toPath());
		}

		// Prepare SDK class loader
		DexClassLoader dexClassLoader = new DexClassLoader(
				internalPath,
				codeCacheDir.getAbsolutePath(),
				null,
				context.getClassLoader()
		);

		// Add TestFairy to default list of loaded dex files (thanks nickcaballero@github)
		if (context.getClassLoader() instanceof BaseDexClassLoader) {
			Object existing = getDexClassLoaderElements((BaseDexClassLoader) context.getClassLoader());
			Object incoming = getDexClassLoaderElements(dexClassLoader);
			Object joined = joinArrays(incoming, existing);
			setDexClassLoaderElements((BaseDexClassLoader) context.getClassLoader(), joined);
		} else {
			throw new UnsupportedOperationException("Class loader not supported");
		}

		// Load the SDK
		return dexClassLoader.loadClass("com.testfairy.TestFairy");
	}

	/**
	 * Sets new paths of elements to the class loaders list of dex files.
	 * @param classLoader
	 * @param elements
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 */
	private static void setDexClassLoaderElements(BaseDexClassLoader classLoader, Object elements)
			throws NoSuchFieldException, IllegalAccessException {
		Class<BaseDexClassLoader> dexClassLoaderClass = BaseDexClassLoader.class;
		Field pathListField = dexClassLoaderClass.getDeclaredField("pathList");
		pathListField.setAccessible(true);
		Object pathList = pathListField.get(classLoader);
		Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
		dexElementsField.setAccessible(true);
		dexElementsField.set(pathList, elements);
	}

	/**
	 * Gets the current path of elements from a class loader.
	 * @param classLoader
	 * @return
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 */
	private static Object getDexClassLoaderElements(BaseDexClassLoader classLoader)
			throws NoSuchFieldException, IllegalAccessException {
		Class<BaseDexClassLoader> dexClassLoaderClass = BaseDexClassLoader.class;
		Field pathListField = dexClassLoaderClass.getDeclaredField("pathList");
		pathListField.setAccessible(true);
		Object pathList = pathListField.get(classLoader);
		Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
		dexElementsField.setAccessible(true);
		Object dexElements = dexElementsField.get(pathList);
		return dexElements;
	}

	/**
	 * Merges arrays if runtime types match in a reflection friendly manner.
	 * @param o1
	 * @param o2
	 * @return
	 */
	private static Object joinArrays(Object o1, Object o2) {
		Class<?> o1Type = o1.getClass().getComponentType();
		Class<?> o2Type = o2.getClass().getComponentType();

		if (o1Type != o2Type)
			throw new IllegalArgumentException();

		int o1Size = Array.getLength(o1);
		int o2Size = Array.getLength(o2);
		Object array = Array.newInstance(o1Type, o1Size + o2Size);

		int offset = 0, i;
		for (i = 0; i < o1Size; i++, offset++)
			Array.set(array, offset, Array.get(o1, i));
		for (i = 0; i < o2Size; i++, offset++)
			Array.set(array, offset, Array.get(o2, i));

		return array;
	}
}
