// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.googlemaps;

import android.app.Activity;
import android.app.Application;
import android.app.FragmentManager;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plugin for controlling a set of GoogleMap views to be shown as overlays on top of the Flutter
 * view. The overlay should be hidden during transformations or while Flutter is rendering on top of
 * the map. A Texture drawn using GoogleMap bitmap snapshots can then be shown instead of the
 * overlay.
 */
public class GoogleMapsPlugin
    implements Application.ActivityLifecycleCallbacks,
        FlutterPlugin,
        ActivityAware,
        DefaultLifecycleObserver,
        MethodChannel.MethodCallHandler {
  static final int CREATED = 1;
  static final int STARTED = 2;
  static final int RESUMED = 3;
  static final int PAUSED = 4;
  static final int STOPPED = 5;
  static final int DESTROYED = 6;
  private final AtomicInteger state = new AtomicInteger(0);
  private int registrarActivityHashCode;
  private FlutterPluginBinding pluginBinding;
  private Lifecycle lifecycle;
  private FragmentManager fragmentManager;
  private MethodChannel utilsMethodChannel;

  private static final String VIEW_TYPE = "plugins.flutter.io/google_maps";
  private static final String UTILS_CHANNEL = "flutter.io/googleMapsPluginUtils";

  public static void registerWith(Registrar registrar) {
    if (registrar.activity() == null) {
      // When a background flutter view tries to register the plugin, the registrar has no activity.
      // We stop the registration process as this plugin is foreground only.
      return;
    }
    final GoogleMapsPlugin plugin =
        new GoogleMapsPlugin(registrar.activity(), registrar.messenger());
    registrar.activity().getApplication().registerActivityLifecycleCallbacks(plugin);
    registrar
        .platformViewRegistry()
        .registerViewFactory(
            VIEW_TYPE,
            new GoogleMapFactory(plugin.state, registrar.messenger(), null, null, registrar, -1));
  }

  public GoogleMapsPlugin() {}

  // FlutterPlugin

  @Override
  public void onAttachedToEngine(FlutterPluginBinding binding) {
    pluginBinding = binding;
  }

  @Override
  public void onDetachedFromEngine(FlutterPluginBinding binding) {
    pluginBinding = null;
  }

  // ActivityAware

  @Override
  public void onAttachedToActivity(ActivityPluginBinding binding) {
    lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(binding);
    lifecycle.addObserver(this);
    pluginBinding
        .getPlatformViewRegistry()
        .registerViewFactory(
            VIEW_TYPE,
            new GoogleMapFactory(
                state,
                pluginBinding.getBinaryMessenger(),
                binding.getActivity().getApplication(),
                lifecycle,
                null,
                binding.getActivity().hashCode()));
    fragmentManager = binding.getActivity().getFragmentManager();
    utilsMethodChannel = new MethodChannel(pluginBinding.getBinaryMessenger(), UTILS_CHANNEL);
    utilsMethodChannel.setMethodCallHandler(this);
  }

  @Override
  public void onDetachedFromActivity() {
    lifecycle.removeObserver(this);
    utilsMethodChannel.setMethodCallHandler(null);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    this.onDetachedFromActivity();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
    lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(binding);
    lifecycle.addObserver(this);
    utilsMethodChannel.setMethodCallHandler(this);
  }

  // DefaultLifecycleObserver methods

  @Override
  public void onCreate(@NonNull LifecycleOwner owner) {
    state.set(CREATED);
  }

  @Override
  public void onStart(@NonNull LifecycleOwner owner) {
    state.set(STARTED);
  }

  @Override
  public void onResume(@NonNull LifecycleOwner owner) {
    state.set(RESUMED);
  }

  @Override
  public void onPause(@NonNull LifecycleOwner owner) {
    state.set(PAUSED);
  }

  @Override
  public void onStop(@NonNull LifecycleOwner owner) {
    state.set(STOPPED);
  }

  @Override
  public void onDestroy(@NonNull LifecycleOwner owner) {
    state.set(DESTROYED);
  }

  // Application.ActivityLifecycleCallbacks methods

  @Override
  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    if (activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    state.set(CREATED);
  }

  @Override
  public void onActivityStarted(Activity activity) {
    if (activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    state.set(STARTED);
  }

  @Override
  public void onActivityResumed(Activity activity) {
    if (activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    state.set(RESUMED);
    utilsMethodChannel.setMethodCallHandler(this);
  }

  @Override
  public void onActivityPaused(Activity activity) {
    if (activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    state.set(PAUSED);
    utilsMethodChannel.setMethodCallHandler(null);
  }

  @Override
  public void onActivityStopped(Activity activity) {
    if (activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    state.set(STOPPED);
  }

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

  @Override
  public void onActivityDestroyed(Activity activity) {
    if (activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    activity.getApplication().unregisterActivityLifecycleCallbacks(this);
    state.set(DESTROYED);
  }

  private GoogleMapsPlugin(Activity activity, BinaryMessenger messenger) {
    this.registrarActivityHashCode = activity.hashCode();
    this.fragmentManager = activity.getFragmentManager();
    this.utilsMethodChannel = new MethodChannel(messenger, UTILS_CHANNEL);
    this.utilsMethodChannel.setMethodCallHandler(this);
  }

  @Override
  public void onMethodCall(MethodCall call, final MethodChannel.Result result) {
    switch (call.method) {
      case "warmUp":
        final MapFragment mapFragment = new MapFragment();
        fragmentManager.beginTransaction().add(mapFragment, "DummyMap").commit();
        mapFragment.getMapAsync(
            new OnMapReadyCallback() {
              @Override
              public void onMapReady(GoogleMap googleMap) {
                try {
                  fragmentManager.beginTransaction().remove(mapFragment).commit();
                  result.success(null);
                } catch (Exception ex) {
                  result.error("WarmUp error", ex.getMessage(), ex.getLocalizedMessage());
                }
              }
            });
        break;
      default:
        result.notImplemented();
        break;
    }
  }
}
