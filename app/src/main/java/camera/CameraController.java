package camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ImageReader;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import java.io.File;

import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

@TargetApi(21)
public class CameraController {

    static final String TAG = CameraController.class.getName();

    @NonNull
    private final Callback mCallback;
    private final Context mContext;
    private final WindowManager mWindowManager;
    private final int mLayoutOrientation;
    private File mFile;
    private AutoFitTextureView mTextureView;
    private String mCameraId;
    private CameraCharacteristics mCameraCharacteristics;
    private Size mPreviewSize;

    private final CompositeSubscription mSubscriptions = new CompositeSubscription();
    private final PublishSubject<Object> mOnPauseSubject = PublishSubject.create();
    private final PublishSubject<Object> mOnShutterClick = PublishSubject.create();
    private final PublishSubject<Object> mOnSwitchCamera = PublishSubject.create();
    private final PublishSubject<SurfaceTexture> mOnSurfaceTextureAvailable = PublishSubject.create();

    public CameraController(Context context, @NonNull Callback callback, @NonNull String photoFileUrl,
                            @NonNull AutoFitTextureView textureView, int layoutOrientation) {
        mContext = context;
        mCallback = callback;
        mLayoutOrientation = layoutOrientation;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mFile = new File(photoFileUrl);
        mTextureView = textureView;
    }

    public void takePhoto() {
        mOnShutterClick.onNext(null);
    }

    public void switchCamera() {
        mOnSwitchCamera.onNext(null);
    }

    public PresenterLifecycle getLifecycle() {
        return mLifecycleImpl;
    }

    private final PresenterLifecycle mLifecycleImpl = new PresenterLifecycle() {
        private static final String SIS_CAMERA_ID = "SIS_CAMERA_ID";

        @Override
        public void onCreate(@Nullable Bundle saveState) {
            if (saveState != null) {
                mCameraId = saveState.getString(SIS_CAMERA_ID);
            }

            CameraManager cameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);

            try {
                if (mCameraId == null) {
                    mCameraId = CameraStrategy.chooseDefaultCamera(cameraManager);
                }

                if (mCameraId == null) {
                    mCallback.onException(new IllegalStateException("Can't find any camera"));
                    return;
                }

                setupPreviewSize(cameraManager);
            }
            catch (CameraAccessException e) {
                mCallback.onException(e);
                return;
            }

            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {

                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    Log.d(TAG, "\tonSurfaceTextureAvailable");
                    mOnSurfaceTextureAvailable.onNext(surface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    //NO-OP
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    Log.d(TAG, "\tonSurfaceTextureDestroyed");
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                }
            });

        }

        @Override
        public void onDestroy() {

        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            outState.putString(SIS_CAMERA_ID, mCameraId);
        }

        @Override
        public void onStart() {

        }

        @Override
        public void onResume() {
            Log.d(TAG, "\tonResume");

            if (mTextureView == null) {
                return;
            }

            subscribe();

            // When the screen is turned off and turned back on, the SurfaceTexture is already
            // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
            // a camera and start preview from here (otherwise, we wait until the surface is ready in
            // the SurfaceTextureListener).
            if (mTextureView.isAvailable()) {
                Log.d(TAG, "\tmTextureView.isAvailable()");
                mOnSurfaceTextureAvailable.onNext(mTextureView.getSurfaceTexture());
            }
        }

        @Override
        public void onPause() {
            Log.d(TAG, "\tonPause");
            mOnPauseSubject.onNext(null);
        }

        @Override
        public void onStop() {

        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {

        }
    };

    private void setupPreviewSize(CameraManager cameraManager) throws CameraAccessException {
        mCameraCharacteristics = cameraManager.getCameraCharacteristics(mCameraId);
        mPreviewSize = CameraStrategy.getPreviewSize(mCameraCharacteristics);
        // We fit the aspect ratio of TextureView to the size of preview we picked.
        // looks like the dimensions we get from camera characteristics are for Landscape layout, so we swap it for portrait
        if (mLayoutOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        }
        else {
            mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
        }
    }

    /**
     * Flow is configured in this method
     */
    private void subscribe() {
        mSubscriptions.clear();

        //this emits state with non-null camera device when camera is opened, and emits camera with null device when it's closed
        Observable<State> openCameraObservable = mOnSurfaceTextureAvailable.asObservable()
            .flatMap(this::initState)
            .doOnNext(this::initImageReader)
            .flatMap(CameraRxWrapper::openCamera)
            .share();

        Observable<State> openSessionObservable = openCameraObservable
            .filter(state -> state.cameraDevice != null)
            .flatMap(CameraRxWrapper::createCaptureSession)
            .share();

        Observable<State> previewObservable = openSessionObservable
            .filter(state -> state.captureSession != null)
            .flatMap(state -> startPreview(state).first())
            .share();

        mSubscriptions.add(Observable.combineLatest(previewObservable, mOnShutterClick, (state, o) -> state)
            .flatMap(this::waitForAf)
            .flatMap(this::waitForAe)
            .flatMap(this::captureStillPicture)
            .subscribe(state -> {
            }, this::onError));

        mSubscriptions.add(Observable.combineLatest(previewObservable, mOnSwitchCamera.first(), (state, o) -> state)
            .doOnNext(this::closeSession)
            .flatMap(state -> openSessionObservable.filter(state1 -> state1.captureSession == null))
            .doOnNext(this::closeCamera)
            .flatMap(state -> openCameraObservable.filter(state2 -> state2.cameraDevice == null))
            .doOnNext(this::closeImageReader)
            .subscribe(this::switchCameraInternal, this::onError));

        mSubscriptions.add(Observable.combineLatest(openCameraObservable, mOnPauseSubject.first(), (state, o) -> state)
            .doOnNext(this::closeSession)
            .doOnNext(this::closeCamera)
            .doOnNext(this::closeImageReader)
            .subscribe(state -> unsubscribe(), this::onError));
    }

    private void onError(Throwable throwable) {
        unsubscribe();
        if (throwable instanceof CameraAccessException) {
            mCallback.onCameraAccessException();
        }
        else if (throwable instanceof OpenCameraException) {
            mCallback.onCameraOpenException(((OpenCameraException) throwable).getReason());
        }
        else {
            mCallback.onException(throwable);
        }
    }

    private void unsubscribe() {
        mSubscriptions.clear();
    }

    @NonNull
    private Observable<State> initState(@NonNull SurfaceTexture surfaceTexture) {
        Log.d(TAG, "\tinitState");
        State state = new State();
        state.surfaceTexture = surfaceTexture;
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        state.previewSurface = new Surface(surfaceTexture);
        state.cameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        state.cameraId = mCameraId;

        return Observable.just(state);
    }

    private void switchCameraInternal(@NonNull State state) {
        Log.d(TAG, "\tswitchCameraInternal");
        try {
            unsubscribe();
            mCameraId = CameraStrategy.switchCamera(state.cameraManager, mCameraId);
            setupPreviewSize(state.cameraManager);
            subscribe();
            mOnSurfaceTextureAvailable.onNext(mTextureView.getSurfaceTexture());
        }
        catch (CameraAccessException e) {
            onError(e);
        }
    }

    private void initImageReader(@NonNull State state) {
        Log.d(TAG, "\tinitImageReader");
        Size sizeForImageReader = CameraStrategy.getStillImageSize(mCameraCharacteristics, mPreviewSize);
        state.imageReader = ImageReader.newInstance(sizeForImageReader.getWidth(), sizeForImageReader.getHeight(), ImageFormat.JPEG, 1);
        mSubscriptions.add(ImageSaverRxWrapper.createOnImageAvailableObservable(state.imageReader)
            .observeOn(Schedulers.io())
            .flatMap(imageReader -> ImageSaverRxWrapper.save(imageReader.acquireLatestImage(), mFile).toObservable())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(file -> mCallback.onPhotoTaken(file.getAbsolutePath(), getLensFacingPhotoType())));
    }

    @NonNull
    private Integer getLensFacingPhotoType() {
        return mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
    }

    @NonNull
    private Observable<State> startPreview(@NonNull State state) {
        Log.d(TAG, "\tstartPreview");
        try {
            return CameraRxWrapper.setRepeatingRequest(state, createPreviewBuilder(state.captureSession, state.previewSurface).build());
        }
        catch (CameraAccessException e) {
            return Observable.error(e);
        }
    }

    private static boolean contains(int[] modes, int mode) {
        if (modes == null) {
            return false;
        }
        for (int i : modes) {
            if (i == mode) {
                return true;
            }
        }
        return false;
    }

    private Observable<CameraController.State> waitForAf(CameraController.State state) {
        try {
            return AfHelper.waitForAf(state, createPreviewBuilder(state.captureSession, state.previewSurface));
        }
        catch (CameraAccessException e) {
            return Observable.error(e);
        }
    }

    @NonNull
    private Observable<State> waitForAe(State state) {
        try {
            return AeHelper.waitForAe(state, createPreviewBuilder(state.captureSession, state.previewSurface));
        }
        catch (CameraAccessException e) {
            return Observable.error(e);
        }
    }

    @NonNull
    private Observable<State> captureStillPicture(State state) {
        Log.d(TAG, "\tcaptureStillPicture");
        try {
            final CaptureRequest.Builder builder = createStillPictureBuilder(state);
            return CameraRxWrapper.capture(state, builder.build());
        }
        catch (CameraAccessException e) {
            return Observable.error(e);
        }
    }

    @NonNull
    private CaptureRequest.Builder createStillPictureBuilder(State state) throws CameraAccessException {
        final CaptureRequest.Builder builder;
        builder = state.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        builder.addTarget(state.imageReader.getSurface());
        setup3Auto(builder);

        int rotation = mWindowManager.getDefaultDisplay().getRotation();
        builder.set(CaptureRequest.JPEG_ORIENTATION, CameraOrientationHelper.getJpegOrientation(mCameraCharacteristics, rotation));
        return builder;
    }

    @NonNull
    CaptureRequest.Builder createPreviewBuilder(CameraCaptureSession captureSession, Surface previewSurface) throws CameraAccessException {
        CaptureRequest.Builder builder = captureSession.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        setup3Auto(builder);
        return builder;
    }

    private void setup3Auto(CaptureRequest.Builder builder) {
        // Enable auto-magical 3A run by camera device
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        Float minFocusDist = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

        // If MINIMUM_FOCUS_DISTANCE is 0, lens is fixed-focus and we need to skip the AF run.
        boolean noAFRun = (minFocusDist == null || minFocusDist == 0);

        if (!noAFRun) {
            // If there is a "continuous picture" mode available, use it, otherwise default to AUTO.
            int[] afModes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
        }

        // If there is an auto-magical flash control mode available, use it, otherwise default to
        // the "on" mode, which is guaranteed to always be available.
        int[] aeModes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        if (contains(aeModes, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
        else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        }

//        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
//        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);

        // If there is an auto-magical white balance control mode available, use it.
        int[] awbModes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (contains(awbModes, CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            // Allow AWB to run auto-magically if this device supports this
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
    }

    private void closeSession(@NonNull State state) {
        Log.d(TAG, "\tcloseSession");
        if (state.captureSession != null) {
            state.captureSession.close();
            state.captureSession = null;
        }
    }

    private void closeCamera(@NonNull State state) {
        Log.d(TAG, "\tcloseCamera");
        if (state.cameraDevice != null) {
            state.cameraDevice.close();
            state.cameraDevice = null;
        }
    }

    private void closeImageReader(@NonNull State state) {
        Log.d(TAG, "\tcloseImageReader");
        if (state.imageReader != null) {
            state.imageReader.close();
            state.imageReader = null;
        }
    }

    public static class State {
        String cameraId;
        Surface previewSurface;
        CameraDevice cameraDevice;
        CameraManager cameraManager;
        ImageReader imageReader;
        CameraCaptureSession captureSession;
        SurfaceTexture surfaceTexture;
        TotalCaptureResult result;
    }

    public interface Callback {
        void onPhotoTaken(@NonNull String photoUrl, @NonNull Integer photoSourceType);

        void onCameraAccessException();

        void onCameraOpenException(@Nullable OpenCameraException.Reason reason);

        void onException(Throwable throwable);
    }

}
