import os
import torch
from ultralytics import YOLO

def train_and_export():
    device = 0 if torch.cuda.is_available() else "cpu"
    print(f"Training on: {torch.cuda.get_device_name(0) if device == 0 else 'CPU'}")

    # Initialize the base YOLOv8 nano model
    model = YOLO("yolov8n.pt")

    # Train the model with the 5 violation classes defined in data.yaml
    model.train(
        data="3riders-2/data.yaml",
        epochs=100,
        imgsz=640,
        batch=16,
        workers=10,
        device=device,
        name="violations_model"
    )

    print("--- Training completed. Starting ONNX export... ---")

    # Export the trained model to ONNX with NMS baked in.
    # This enables NMS on-device automatically and matches processNmsOutput in ObjectDetector.kt
    onnx_path = model.export(
        format="onnx",
        imgsz=640,
        nms=True,
        opset=12,
        simplify=True
    )

    print("---")
    print(f"Success! Model successfully exported to ONNX: {onnx_path}")

if __name__ == "__main__":
    train_and_export()
