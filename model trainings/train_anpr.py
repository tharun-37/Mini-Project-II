import os
import torch
from ultralytics import YOLO

def train_and_export_anpr():
    device = 0 if torch.cuda.is_available() else "cpu"
    print(f"Training ANPR on: {torch.cuda.get_device_name(0) if device == 0 else 'CPU'}")

    # Initialize the base YOLOv8 nano model
    model = YOLO("yolov8n.pt")

    # Train the model with the 4 classes (with/without helmet, rider, number plate) defined in coco128.yaml
    model.train(
        data="numberplatedataset/coco128.yaml",
        epochs=100,
        imgsz=320,  # Trained at 320px to match optimized mobile inference size
        batch=16,
        workers=10,
        device=device,
        name="anpr_model"
    )

    print("--- Training completed. Starting ANPR ONNX export... ---")

    # Export the trained model to ONNX.
    # CRUCIAL:
    # 1. imgsz=320 - Matches the expected imgSize in ObjectDetector.kt
    # 2. nms=False - Does NOT bake NMS in, as Kotlin handles custom NMS via processYoloV8Output
    onnx_path = model.export(
        format="onnx",
        imgsz=320,
        nms=False,
        opset=12,
        simplify=True
    )

    print("---")
    print(f"Success! ANPR Model successfully exported to ONNX: {onnx_path}")

if __name__ == "__main__":
    train_and_export_anpr()
