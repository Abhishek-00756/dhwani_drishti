import tensorflow as tf
interpreter = tf.lite.Interpreter(model_path="app/src/main/assets/yolov8n_fp16.tflite")
interpreter.allocate_tensors()
print("INPUT:", interpreter.get_input_details())
print("OUTPUT:", interpreter.get_output_details())
