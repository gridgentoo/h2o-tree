package ai.h2o.automl.targetencoding;

import water.Iced;

import java.util.Map;

public class TargetEncodingParams extends Iced {

  private boolean _withBlendedAvg;
  private BlendingParams _blendingParams;
  private byte _holdoutType;
  private double _noiseLevel;

  private boolean _imputeNAsWithNewCategory = true;

  public TargetEncodingParams( BlendingParams blendingParams, byte holdoutType, double noiseLevel) {
    if(blendingParams != null) this._withBlendedAvg = true;
    this._blendingParams = blendingParams;
    this._holdoutType = holdoutType;
    this._noiseLevel = noiseLevel;
  }
  
  public TargetEncodingParams( byte holdoutType) {
    this._withBlendedAvg = false;
    this._blendingParams = null;
    this._holdoutType = holdoutType;
    this._noiseLevel = 0;
  }
  
  public TargetEncodingParams( Map<String, Object> paramsMap) {
    _withBlendedAvg = (double) paramsMap.get("_withBlending") == 1.0;
    _blendingParams = new BlendingParams((double) paramsMap.get("_inflection_point"), (double) paramsMap.get("_smoothing"));
    
    double value = (double) paramsMap.get("_holdoutType");
    if(value == 0.0) _holdoutType = TargetEncoder.DataLeakageHandlingStrategy.LeaveOneOut;
    if(value == 1.0) _holdoutType = TargetEncoder.DataLeakageHandlingStrategy.KFold;
    if(value == 2.0) _holdoutType = TargetEncoder.DataLeakageHandlingStrategy.None;

    Object noise_level = paramsMap.get("_noise_level");
    _noiseLevel = noise_level != null ? (double) noise_level : 0.0;
  }
  
  public static TargetEncodingParams DEFAULT = new TargetEncodingParams(new BlendingParams(10, 5), TargetEncoder.DataLeakageHandlingStrategy.KFold, 0.01);

  public BlendingParams getBlendingParams() {
    return _blendingParams;
  }

  @Override
  public String toString() {
    String representation = null;
    if( isWithBlendedAvg()) { // TODO avoid duplication
      representation = "TE params: holdout_type = " + getHoldoutType() + " , blending = " + isWithBlendedAvg() + ", inflection_point = " + getBlendingParams().getK() +
              " , smoothing = " + getBlendingParams().getF() + " , noise_level = " + getNoiseLevel();
    }
    else {
      representation = "TE params: holdout_type = " + getHoldoutType() + " , noise_level = " + getNoiseLevel();
    }
    return representation;
  }

  public byte getHoldoutType() {
    return _holdoutType;
  }

  public double getNoiseLevel() {
    return _noiseLevel;
  }

  public boolean isWithBlendedAvg() {
    return _withBlendedAvg;
  }

  public boolean isImputeNAsWithNewCategory() {
    return _imputeNAsWithNewCategory;
  }
}
